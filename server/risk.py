"""Explainable demonstration rules, not a fraud probability or verdict."""
import re
from collections import deque


class RiskEngine:
    def __init__(self):
        self.context = deque(maxlen=6)

    def assess(self, text: str) -> dict:
        self.context.append(text.lower().replace("ё", "е"))
        context = " ".join(self.context)
        reasons = []
        request = r"(?:назов|скаж|продикт|сообщ|отправ|дикту|введите|кодты.{0,20}(?:айт|жібер))"
        if re.search(request + r".{0,60}(?:код|парол)", context) or re.search(r"кодты.{0,20}(?:айт|жібер)", context):
            reasons.append("Просьба сообщить код или пароль")
        if re.search(r"безопасн\w*\s+счет|резервн\w*\s+счет|қ[ао]уіпсіз\s+шот", context):
            reasons.append("Упоминание «безопасного счёта»")
        if re.search(r"(?:установ|скача).{0,40}(?:anydesk|rustdesk|анидеск|удаленн)", context):
            reasons.append("Просьба установить удалённый доступ")
        if re.search(r"никому не (?:говор|рассказ)|не (?:звоните|советуйтесь).{0,30}(?:родствен|близк)", context):
            reasons.append("Просьба скрыть разговор от близких")
        pressure = bool(re.search(r"срочно|немедленно|прямо сейчас|шұғыл", context))
        payment = bool(re.search(r"перевед|перевест|перевод денег|ақша.{0,20}аудар", context))
        if pressure and payment:
            reasons.append("Давление с требованием перевода денег")
        level = "high" if reasons else "low"
        message = "Есть тревожные признаки. Не сообщайте коды; проверьте просьбу через официальный канал."
        if not reasons and payment:
            level = "medium"
            reasons.append("Обсуждается перевод денег — проверьте получателя")
            message = "Не торопитесь с переводом. Свяжитесь с близким человеком."
        if level == "low":
            message = "Явных признаков по этим правилам не найдено. Это не гарантия безопасности."
        return {"type": "risk", "level": level, "reasons": reasons, "message": message}
