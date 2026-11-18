import asyncio
import base64
import pytest
from fastapi.testclient import TestClient
from server.app import create_app
from server.asr import AnalysisError, validate_fraud_report

CID = 'demo-call-123456'
START = dict(type='start', call_id=CID, consent=True, analysis_provider='google',
             format='pcm_s16le', sample_rate=16000, channels=1, language='ru')
REPORT = dict(risk_level='DANGER', scam_type='Запрос SMS-кода', reason='Не сообщайте код.')


class Backend:
    def __init__(self, failure=None):
        self.frames = []
        self.closed = False
        self.failure = failure

    async def run(self, queue, emit, ready, language):
        ready.set_result(None)
        try:
            while True:
                data = await queue.get()
                if data is None:
                    return
                self.frames.append(data)
                if self.failure:
                    raise self.failure
                await emit({'type': 'fraud_report', 'data': REPORT})
        finally:
            self.closed = True


@pytest.fixture(autouse=True)
def env(monkeypatch):
    monkeypatch.setenv('ANALYSIS_MODE', 'diagnostic')


def subscribe(ws, cid=CID):
    ws.send_json(dict(type='subscribe', call_id=cid, consent=True))
    assert ws.receive_json() == dict(type='SUBSCRIBED', call_id=cid)


def start(media, android):
    media.send_json(START)
    assert media.receive_json()['type'] == 'ready'
    assert android.receive_json()['type'] == 'ANALYSIS_STARTED'


def test_automatic_ids_route_to_user_and_change_between_calls():
    with TestClient(create_app(Backend())) as client:
        with client.websocket_connect('/ws/android') as android, client.websocket_connect('/ws/android') as other:
            for ws, uid in [(android, 'alice'), (other, 'bob')]:
                ws.send_json(dict(type='subscribe', user_id=uid, consent=True))
                assert ws.receive_json() == dict(type='SUBSCRIBED', user_id=uid)
            with client.websocket_connect('/ws/android') as duplicate:
                duplicate.send_json(dict(type='subscribe', user_id='alice', consent=True))
                assert duplicate.receive_json()['code'] == 'busy'
            ids = set()
            for _ in range(2):
                with client.websocket_connect('/media-stream') as media:
                    event = {k: v for k, v in START.items() if k != 'call_id'}
                    media.send_json(dict(event, user_id='alice'))
                    ready = media.receive_json()
                    assert ready['type'] == 'ready'
                    cid = ready['call_id']
                    assert cid not in ids
                    ids.add(cid)
                    assert android.receive_json() == dict(type='CALL_ASSIGNED', user_id='alice', call_id=cid)
                    assert android.receive_json()['type'] == 'ANALYSIS_STARTED'
                    media.send_bytes(bytes(320))
                    assert android.receive_json() == dict(type='FRAUD_ALERT', call_id=cid, data=REPORT)
                    other.send_json(dict(type='ping'))
                    assert other.receive_json() == dict(type='pong', user_id='bob')
                    media.send_json(dict(type='stop'))
                    assert media.receive_json()['type'] == 'done'
                    assert android.receive_json() == dict(type='CALL_ENDED', call_id=cid, complete=True)


@pytest.mark.parametrize('identity', [{'user_id': 'missing'}, {'user_id': '../bad'},
                                    {'user_id': 'alice', 'call_id': CID}])
def test_auto_identity_rejected_without_valid_subscriber(identity):
    with TestClient(create_app(Backend())) as client:
        with client.websocket_connect('/media-stream') as media:
            event = {k: v for k, v in START.items() if k != 'call_id'}
            media.send_json(dict(event, **identity))
            assert media.receive_json()['type'] == 'ERROR'
        assert client.get('/health').json()['busy'] is False


@pytest.mark.parametrize('binary', [False, True])
def test_audio_to_matching_android_and_clean_stop(binary):
    backend = Backend()
    with TestClient(create_app(backend)) as client:
        with client.websocket_connect('/ws/android') as android, client.websocket_connect('/media-stream') as media:
            subscribe(android)
            start(media, android)
            pcm = b'\x01\x00' * 160
            if binary:
                media.send_bytes(pcm)
            else:
                media.send_json({'event': 'start', 'start': {'mediaFormat': {
                    'encoding': 'audio/x-l16', 'sampleRate': 16000, 'channels': 1}}})
                media.send_json({'event': 'media', 'media': {'payload': base64.b64encode(pcm).decode()}})
            assert android.receive_json() == dict(type='FRAUD_ALERT', call_id=CID, data=REPORT)
            media.send_json({'event': 'stop'})
            assert media.receive_json()['type'] == 'done'
            assert android.receive_json() == dict(type='CALL_ENDED', call_id=CID, complete=True)
            assert backend.frames == [pcm]
            assert backend.closed
        assert client.get('/health').json()['busy'] is False
        assert client.get('/').status_code == 200


def test_subscriber_cannot_be_replaced_or_receive_other_calls():
    with TestClient(create_app(Backend())) as client:
        with client.websocket_connect('/ws/android') as first, client.websocket_connect('/ws/android') as other:
            subscribe(first)
            subscribe(other, 'different-call-123')
            with client.websocket_connect('/ws/android') as duplicate:
                duplicate.send_json(dict(type='subscribe', call_id=CID, consent=True))
                assert duplicate.receive_json()['code'] == 'busy'
            with client.websocket_connect('/media-stream') as media:
                start(media, first)
                media.send_bytes(bytes(320))
                assert first.receive_json()['type'] == 'FRAUD_ALERT'
                other.send_json({'type': 'ping'})
                assert other.receive_json()['type'] == 'pong'  # no cross-call alert queued
                media.send_json({'type': 'stop'})
                media.receive_json()
                first.receive_json()


@pytest.mark.parametrize('change', [{'consent': False}, {'sample_rate': 8000}, {'channels': 2}, {'format': 'wav'}, {'call_id': '../bad'}])
def test_invalid_start_never_reserves_slot(change):
    with TestClient(create_app(Backend())) as client, client.websocket_connect('/media-stream') as ws:
        ws.send_json({**START, **change})
        assert ws.receive_json()['code'] == 'invalid_audio'
        assert not client.get('/health').json()['busy']


def test_requires_android_and_enabled_analysis():
    with TestClient(create_app()) as client:
        with client.websocket_connect('/media-stream') as ws:
            ws.send_json(START)
            assert ws.receive_json()['code'] == 'android_required'
        with client.websocket_connect('/ws/android') as android, client.websocket_connect('/media-stream') as ws:
            subscribe(android)
            ws.send_json(START)
            assert ws.receive_json()['code'] == 'analysis_unavailable'


@pytest.mark.parametrize('frame', [b'x', b'', b'RIFF' + bytes(100), bytes(16002), {'event': 'media', 'media': {'payload': '!bad'}}, {'event': 'start', 'start': {'mediaFormat': {'encoding': 'audio/x-mulaw', 'sampleRate': 8000, 'channels': 1}}}])
def test_bad_frames_notify_android_and_release(frame):
    backend = Backend()
    with TestClient(create_app(backend)) as client:
        with client.websocket_connect('/ws/android') as android, client.websocket_connect('/media-stream') as ws:
            subscribe(android)
            start(ws, android)
            ws.send_bytes(frame) if isinstance(frame, bytes) else ws.send_json(frame)
            assert ws.receive_json()['code'] == 'invalid_audio'
            assert android.receive_json()['type'] == 'ANALYSIS_ERROR'
        assert not client.get('/health').json()['busy']
        assert backend.closed


def test_upstream_failure_reported_without_further_audio():
    backend = Backend(AnalysisError('quota_exceeded', 'Quota exhausted'))
    with TestClient(create_app(backend)) as client:
        with client.websocket_connect('/ws/android') as android, client.websocket_connect('/media-stream') as media:
            subscribe(android)
            start(media, android)
            media.send_bytes(bytes(320))
            assert media.receive_json()['code'] == 'quota_exceeded'
            assert android.receive_json()['code'] == 'quota_exceeded'
        assert backend.closed
        assert not client.get('/health').json()['busy']


def test_media_disconnect_releases_and_marks_incomplete():
    backend = Backend()
    with TestClient(create_app(backend)) as client, client.websocket_connect('/ws/android') as android:
        subscribe(android)
        with client.websocket_connect('/media-stream') as media:
            start(media, android)
            media.close()
            assert android.receive_json() == dict(type='CALL_ENDED', call_id=CID, complete=False)
        assert backend.closed
        assert not client.get('/health').json()['busy']


def test_android_disconnect_stops_analysis():
    backend = Backend()
    with TestClient(create_app(backend)) as client, client.websocket_connect('/media-stream') as media:
        with client.websocket_connect('/ws/android') as android:
            subscribe(android)
            start(media, android)
        assert media.receive_json()['code'] == 'android_disconnected'
    assert backend.closed


def test_existing_audio_slot_is_shared():
    with TestClient(create_app(Backend())) as client:
        with client.websocket_connect('/ws/android') as android, client.websocket_connect('/ws/audio') as legacy:
            subscribe(android)
            legacy.send_json(dict(type='start', sample_rate=16000, channels=1, format='pcm_s16le', consent=True, analysis_provider='google'))
            assert legacy.receive_json()['type'] == 'ready'
            with client.websocket_connect('/media-stream') as media:
                media.send_json(START)
                assert media.receive_json()['code'] == 'busy'
            legacy.send_json({'type': 'stop'})
            legacy.receive_json()


@pytest.mark.parametrize('report', [{}, {**REPORT, 'risk_level': 'SAFE'}, {**REPORT, 'reason': ''}, {**REPORT, 'extra': 1}])
def test_invalid_report_rejected(report):
    assert validate_fraud_report(report) is None


def test_fraud_sdk_tool_response():
    from google.genai import types
    from tests.test_gemini import MockSession, MockClient
    from server.asr import GeminiLive, AudioAnalysis
    async def run():
        session = MockSession()
        client = MockClient(session)
        got = asyncio.Event()
        events = []
        async def emit(event):
            events.append(event)
            got.set()
        analysis = AudioAnalysis(GeminiLive(client_factory=lambda **kwargs: client, fraud_alerts=True), emit, 'kk')
        try:
            await analysis.start()
            types.LiveConnectConfig.model_validate(client.connections[0]['config'])
            await session.incoming.put(types.LiveServerMessage(tool_call=types.LiveServerToolCall(function_calls=[
                types.FunctionCall(id='fraud-1', name='report_fraud', args=REPORT)])))
            await asyncio.wait_for(got.wait(), 1)
            await asyncio.sleep(0)
            assert events == [{'type': 'fraud_report', 'data': REPORT}]
            assert session.replies[0].id == 'fraud-1'
            assert 'this language: ru' in client.connections[0]['config']['system_instruction']
        finally:
            await analysis.close()
        assert client.closed
    asyncio.run(run())
