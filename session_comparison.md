# Сравнение работы с сессиями: Hermes WebUI (Python) vs Mobile App (Kotlin)

## Ключевые различия

| Аспект | Hermes WebUI (сервер) | Mobile App |
|--------|----------------------|------------|
| **Механизм аутентификации** | Cookie `hermes_session` (HttpOnly, SameSite=Lax, HMAC-подпись) | Та же cookie, но хранится в plaintext SharedPreferences |
| **Выход (logout)** | `POST /api/auth/logout` → инвалидирует токен на сервере, очищает cookie | Только удаляет `webui_url` + `webui_cookies` из SharedPreferences. **Серверный logout НЕ вызывается** |
| **Типы "сессий"** | Два разных понятия: **Chat Session** (разговор с AI) и **Auth Session** (браузерная аутентификация) | Использует только **Chat Session (session_id)** как идентификатор диалога. Понятия Auth Session нет |
| **Хранение session_id** | Сервер — in-memory LRU cache + JSON-файлы на диске | Android — только в памяти (`rememberSaveable`), не сохраняет session_id между запусками |
| **Cookie Jar** | Единый сервер, управляет Set-Cookie | Два независимых `OkHttpClient`, но общий `PersistentCookieJar` через SharedPreferences |
| **CSRF-защита** | `X-Hermes-CSRF-Token` header на POST/PUT/PATCH/DELETE — **требуется** | Не отправляется. **Все mutation-запросы будут падать с 403 при включённой аутентификации** |
| **Streaming** | WebSocket + SSE через `StreamChannel` и `SessionChannel` | Только SSE (plain HTTP GET с парсингом строк). WebSocket/WebRTC нет |
| **Real-time sync** | SSE Event Bus (`session_events.py`), `gateway_watcher.py` (polling state.db), несколько вкладок синхронизируются | Нет межсессионной синхронизации. Одно устройство, один экран |
| **Auth проверка** | `check_auth()` middleware на каждый запрос — проверяет HMAC-подпись + `_sessions` dict + expiry | **Не проверяет** — надеется, что OkHttp сам подложит валидную cookie. При expiry — 401 или redirect без auto-relogin |
| **Логин** | Password или Passkey (WebAuthn) + rate limiting (5 попыток/60s/IP) | Только password. После `POST /login` делает повторный `GET /auth/status` для верификации |
| **Logout на сервере** | `invalidate_session()` → удаляет токен из `_sessions`, lazy prune expired | **Не делает** `POST /api/auth/logout` |

## Критические проблемы в Mobile App

1. **CSRF токен не отправляется** — если на сервере включена аутентификация (`HERMES_WEBUI_PASSWORD`), все POST-запросы (`/api/session/new`, `/api/chat/start`, `/api/session/delete` и т.д.) будут возвращать 403.

2. **Logout не инвалидирует сессию на сервере** — токен остаётся в `_sessions` сервера до естественного expiry (30 дней).

3. **Cookies хранятся без шифрования** — в plaintext SharedPreferences, доступны при ADB backup или на rooted устройствах.

4. **Нет автоматического relogin** — при expiry сессии пользователь увидит ошибку, но не будет перенаправлен на логин.

5. **Нет сохранения session_id** — при перезапуске приложения последний активный диалог не восстанавливается (в веб-версии используется `localStorage`).

6. **Два независимых OkHttpClient** — хотя cookie jar общий, настройки таймаутов и логирования не синхронизированы между `HermesAuthClient` и `HermesApiClient`.
