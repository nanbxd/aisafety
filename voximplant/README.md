# Voximplant → сервер → Gemini Live → Android

Сервер принимает копию голоса выбранного участника из **уже маршрутизируемого через Voximplant звонка**. Обычный SIM-звонок сам по себе в этот сокет не попадёт. Звонок между двумя людьми организует ваш VoxEngine-сценарий (SDK/SIP/PSTN); этот модуль добавляет анализ и не набирает номера.

## Настройка Render

Используйте существующий Docker-сервис, один процесс/worker. Параметры из [шаблона окружения](../.env.example):

```dotenv
ANALYSIS_MODE=gemini
GEMINI_API_KEY=ваш_серверный_ключ
GEMINI_MODEL=gemini-3.8-live
PUBLIC_BASE_URL=https://YOUR-SERVICE.onrender.com
```

Ключ Google вводится только в Render Environment. Для приёма WebSocket-потока ключ Management API Voximplant на сервере не нужен. Доступ вашего Google-проекта к выбранной модели нужно проверить живым вызовом; тесты используют подставной Gemini.

- Проверка HTTP: `https://YOUR-SERVICE.onrender.com/health` (также `/`).
- Android: `wss://YOUR-SERVICE.onrender.com/ws/android`.
- VoxEngine: `wss://YOUR-SERVICE.onrender.com/media-stream`.

Пути обязательны. `wss://://onrender.com` — некорректный адрес.

## Порядок подключения

1. Подпишите Android по постоянному `user_id`. В готовом [входящем сценарии](inbound-test.js) это точное значение `call.callerid()` звонящего. Сервер создаёт новый `call_id` автоматически для каждого разговора. Для двухстороннего маршрута передайте ID владельца Android явно: он может отличаться от стороны, чей голос анализируется.
2. Получите согласие обоих участников на передачу голоса в Google. Android подключается первым и отправляет:

```json
{"type":"subscribe","user_id":"alice","consent":true}
```

3. Дождитесь `{"type":"SUBSCRIBED","user_id":"alice"}`. Android отправляет `{"type":"ping"}` каждые 30 секунд; сервер отвечает `pong`. Через 90 секунд без сообщений подписка закрывается.
4. В существующем сценарии VoxEngine включите [media-stream.js](media-stream.js). После соединения звонка и подтверждённого согласия вызовите:

```javascript
// remoteCall — нужная Call-сторона вашего уже соединённого звонка.
// userId — постоянный ID получателя, по которому подписан Android.
const monitor = attachFraudMonitor(
  remoteCall, userId, true,
  'wss://YOUR-SERVICE.onrender.com/media-stream'
);
// Для досрочного прекращения анализа: monitor.stop();
```

`true` здесь означает **уже полученное согласие**, а не заменяет диалог согласия. Сохраните существующие `sendMediaBetween`/маршрутизацию двух участников. Не подключайте `ws.sendMediaTo(call)`: сервер не воспроизводит ответы Gemini в разговор. Передавайте только нужную сторону; смешанный звук не даёт надёжно определить говорящего.

Помощник открывает сокет, отправляет стартовый JSON и ждёт `ready`. Только затем вызывает `call.sendMediaTo(ws, {encoding: WebSocketAudioEncoding.PCM16_16KHZ})`. При потере мониторинга сам телефонный звонок не завершается. Отсутствие анализа отражается в Android; приложение должно также показывать потерю своего сокета.

## Протокол медиа

Первое прикладное сообщение (после необязательного Voximplant `event:init`):

```json
{"customEvent":"safety-start","type":"start","user_id":"alice","consent":true,"analysis_provider":"google","format":"pcm_s16le","sample_rate":16000,"channels":1,"language":"ru"}
```

Поддерживаются `ru` и `kk`; предупреждения выдаются по-русски. После `ready` принимаются:

- стандартные Voximplant `event:start`, `event:media` с `media.payload` в base64 и `event:stop`;
- бинарные кадры **raw PCM signed 16-bit little-endian, 16000 Hz, mono** и `{"type":"stop"}`.

Размер декодированного кадра: 2–16000 байт, чётный. WAV, MP3, μ-law/8 кГц нельзя просто пометить как PCM16. В сценарии частота задаётся явно, в метаданных проверяется. На завершении `stop` сервер ждёт финальных результатов Gemini; внезапный обрыв отмечается `complete:false`. Лимиты: одна активная сессия анализа на весь сервер (включая старые WebRTC/микрофонные маршруты), 15 минут, 20 секунд без аудиокадров, ограниченные очереди. Тишину отправляйте обычными PCM-кадрами.

## События Android

```json
{
  "type":"FRAUD_ALERT",
  "call_id":"demo-call-123456",
  "data":{
    "risk_level":"DANGER",
    "scam_type":"Запрос SMS-кода",
    "reason":"Собеседник просит код подтверждения. Не сообщайте его."
  }
}
```

Перед началом анализа Android получает `{"type":"CALL_ASSIGNED","user_id":"alice","call_id":"<новый UUID>"}`. Подписка пользователя сохраняется для следующих звонков.

`risk_level`: `SUSPICIOUS` или `DANGER`. Другие события: `SUBSCRIBED`, `ANALYSIS_STARTED`, `TRANSCRIPT` (`text`), `ANALYSIS_ERROR` (`code`, `message`, `complete:false`), `CALL_ENDED` (`complete`), `ERROR`, `pong`. `complete:true` означает штатное завершение обработки полученного потока, а не гарантию отсутствия мошенничества.

Пример подписки через уже используемый в Android OkHttp (вызовы UI перенесите в главный поток):

```kotlin
var sessionCallId: String? = null
val userId = "alice" // Must match the recipient ID in the scenario.
val request = Request.Builder()
    .url("wss://YOUR-SERVICE.onrender.com/ws/android").build()
val socket = client.newWebSocket(request, object : WebSocketListener() {
    override fun onOpen(ws: WebSocket, response: Response) {
        ws.send(JSONObject().put("type", "subscribe")
            .put("user_id", userId).put("consent", true).toString())
    }
    override fun onMessage(ws: WebSocket, text: String) {
        val event = JSONObject(text)
        if (event.optString("type") == "CALL_ASSIGNED") {
            sessionCallId = event.getString("call_id")
            return
        }
        if (event.optString("type") != "ERROR" &&
            event.optString("call_id") != sessionCallId) return
        when (event.optString("type")) {
            "FRAUD_ALERT" -> {
                val data = event.getJSONObject("data")
                // runOnUiThread: показать risk_level, scam_type и reason.
            }
            "ANALYSIS_ERROR", "ERROR" -> {
                // runOnUiThread: явно показать, что анализ недоступен.
            }
        }
    }
    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        // runOnUiThread: связь с мониторингом потеряна; не показывать «безопасно».
    }
})
// Пока экран мониторинга открыт: socket.send("{\"type\":\"ping\"}") каждые 30 с.
// При завершении: отменить heartbeat и socket.close(1000, "screen closed").
```

Это контракт для Kotlin, **не подключённый экран Android**. Текущее приложение по-прежнему открывает WebRTC-страницу; для реального звонка через Voximplant нужен клиент/маршрут Voximplant и экран, подписанный на этот endpoint. Фоновые push-уведомления этот WebSocket не реализует.

## Границы доступа и проверки

Общего access token нет по требованию проекта. Endpoints публичны; знание `user_id` или `call_id` не заменяет авторизацию. Caller ID также не подтверждает личность. Подписка не может вытеснить уже подключённый Android; уведомления не рассылаются другим звонкам. Используйте для согласованного демо, не для публичного многопользовательского сервиса. Аудио, текст и содержимое алертов не пишутся в серверные логи или на диск.

Старый режим с заранее согласованным `call_id` поддерживается. Указывать одновременно `user_id` и `call_id` нельзя. Несколько пользователей могут ожидать звонка, но анализируется только один разговор одновременно.

Проверка локально: `./.venv/Scripts/python.exe -m pytest tests/test_voximplant.py -q`. Тесты проверяют протокол и жизненный цикл с подставным Gemini; живой звонок в аккаунте Voximplant и реальное распознавание не выполнены.

Источники: [Voximplant Media Streams](https://voximplant.com/docs/guides/media-streams/websocket), [официальные типы VoxEngine](https://cdn.voximplant.com/voxengine_typings/voxengine.d.ts), [Gemini Live: аудио и конфигурация](https://aistudio.google.com/docs/live-api/capabilities).

