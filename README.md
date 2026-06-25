# ToInet-Mobile - менеджер для обеспечения веб-доступа

**ToInet-Mobile** - это мобильное приложение-лаунчер для Android, объединяющее в себе несколько инструментов обхода блокировок: **Tor (ЛМ)**, **Telegram WebSocket Proxy (TGWS)**, **ByeDPI**, **vk-turn-proxy** и **Socks-Reabilitator**. 

Приложение позволяет управлять этими инструментами и маршрутизировать трафик через VPN-сервис устройства или использовать их как локальные прокси-серверы для отдельных приложений (например, Telegram).

---

## Основные возможности
*   **ByeDPI** 
*   **Tor** 
*   **Telegram WebSocket Proxy (TGWS)**
*   **Socks-Reabilitator**
*   **vk-turn**
*   **FakeVPN**
*   **Режим VPN**
---

## Порты по умолчанию

Для бесконфликтной работы модули ToInet-Mobile используют фиксированные локальные порты:

| Инструмент | Порт | Тип прокси |
| :--- | :--- | :--- |
| **Tor Socks5** | `5242` | SOCKS5 |
| **Tor HTTP** | `5267` | HTTP |
| **TGWS Proxy** | `1480` | SOCKS5 |
| **Socks-Reabilitator** | `1788` | SOCKS5 |

---

## Быстрое подключение к Telegram

Вы можете быстро применить настройки локального прокси напрямую в клиент Telegram в один клик:

*   [Подключить TGWS (1480)](https://t.me/socks?server=127.0.0.1&port=1480)
*   [Подключить Tor (5242)](https://t.me/socks?server=127.0.0.1&port=5242)
*   [Подключить Socks-Reabilitator (1788)](https://t.me/socks?server=127.0.0.1&port=1788)


> [!WARNING]
> **Отваливается интернет при включении режима VPN.**
> В таком случае может потребоваться выключить частный DNS (Private DNS - DOT / DOH) в настройках сети.

## Дисклеймер
В случае расследования какой-либо федеральной структуры или подобного, я не имею никакого отношения к этой группе или к людям в ней, я не знаю, как я здесь оказался, возможно, добавлен третьей стороной, я не поддерживаю никаких действий членов этой группы.

**Лицензия:** Все права принадлежат тем, кому они принадлежат.

*   [ЛМ (Tor)](https://torproject.org)
*   [Orbot](https://github.com/guardianproject/orbot-android)
*   [byedpiandroid](https://github.com/dovecoteescapee/byedpiandroid)
*   [byedpi](https://github.com/hufrea/byedpi)
*   [ByeByeDPI](https://github.com/romanvht/ByeByeDPI/)
*   [TGWS](https://github.com/Flowseal/tg-ws-proxy)
*   [Socks-Reabilitator](https://github.com/AlexeyRF/Socks-Reabilitator)
*   [vk-turn](https://github.com/cacggghp/vk-turn-proxy)
*   [vk-turn-android](https://github.com/samosvalishe/turn-proxy-android)
