# ToInet-Mobile
**Версия для ПК:** [ToInet](https://github.com/AlexeyRF/ToInet)

**ToInet-Mobile** — это мобильное приложение-лаунчер для Android, объединяющее в себе передовые инструменты обхода блокировок: **Easy-VPN (Xray-core)**, **Zapret (tpws)** со стратегиями Flowseal, **ByeDPI**, **Tor (ЛМ)**, **Telegram WebSocket Proxy (TGWS)**, **Socks-Reabilitator**, **FakeVPN** и **Opera Proxy**. 

Приложение позволяет как маршрутизировать трафик всего устройства (или выбранных приложений) через системный VPN-сервис (`tun2socks`), так и использовать инструменты как локальные SOCKS5/HTTP-прокси для отдельных клиентов.

---

## Основные возможности

*   **Easy-VPN (Xray-core)**:
    *   Полноценный клиент VLESS на базе современного ядра `Xray-core`.
    *   Поддержка протоколов и транспортов: **VLESS Reality**, **TLS**, **xhttp (splithttp)**, **WebSocket**, **gRPC**, **HTTPUpgrade**.
    *   Встроенный кэш проверенных рабочих серверов (мгновенный старт за миллисекунды без ожидания проверки сети).
    *   Фоновая подгрузка и автообновление подписок (включая зеркала на GitVerse и GitHub) с проверкой доступности узлов по TCP ping и балансировкой (`leastPing`).
*   **Zapret (tpws)**:
    *   Автономный движок `tpws` от bol-van, скомпилированный под Android.
    *   **22 готовые стратегии от Flowseal** (`zapret-discord-youtube`) в удобном выпадающем меню: `General`, `ALT`, `FAKE`, `SUPER-FAKE`, `Discord Fix` и др.
    *   **Встроенный тестер стратегий Zapret**: автоматическая проверка пресетов на реальных заблокированных сайтах (YouTube, Discord, Twitter/X, Rutracker и др.) с отображением кода ответа HTTP, задержки и итогового статуса.
    *   Возможность ручного ввода любых аргументов `tpws`.
*   **ByeDPI**:
    *   Локальный SOCKS5-прокси с гибкими методами фрагментации TCP, десинхронизации TLS ClientHello и HTTP-модификаций.
    *   Встроенный тестер стратегий ByeDPI для подбора оптимальных параметров.
*   **Режим системного VPN (tun2socks)**:
    *   Единое чистое системное уведомление при работе в режиме VPN (без дублирования фоновых уведомлений SOCKS-прокси).
    *   Раздельное туннелирование (Split Tunneling) с возможностью исключения или включения конкретных приложений.
    *   Исключение самого приложения из VPN-петли во избежание циклов маршрутизации.
*   **Tor (ЛМ)**:
    *   Встроенный onion-роутер для полной анонимности и обхода жестких ограничений.
*   **TGWS**:
    *   Специализированный WebSocket-прокси для мгновенного подключения Telegram.
*   **Socks-Reabilitator**:
    *   Проксирование трафика через TURN-серверы для обхода белых списков и блокировок по протоколам (*модуль vk-turn закомментирован*).
*   **FakeVPN**:
    *   Прямое туннелирование с опциональной подменой SNI (FakeTLS).
*   **Opera Proxy**:
    *   Маршрутизация через прокси-серверы Opera в нативном SOCKS5-режиме (`-socks-mode`).

---

## Порты по умолчанию

Для бесконфликтной одновременной или раздельной работы модули ToInet-Mobile используют фиксированные локальные порты:

| Инструмент             | Порт   | Тип прокси | Описание                                 |
|:-----------------------|:-------|:-----------|:-----------------------------------------|
| **Byedpi**             | `1080` | SOCKS5     | Локальный DPI-десинхронизатор ByeDPI     |
| **Zapret (tpws)**      | `1081` | SOCKS5     | Zapret tpws с пресетами Flowseal         |
| **Easy-VPN (Xray)**    | `1791` | SOCKS5     | Балансировщик VLESS узлов на Xray-core   |
| **Tor Socks5**         | `5242` | SOCKS5     | Onion маршрутизатор Tor                  |
| **Tor HTTP**           | `5267` | HTTP       | HTTP-интерфейс Tor                       |
| **TGWS Proxy**         | `1480` | SOCKS5     | Прокси для Telegram через WebSockets     |
| **Socks-Reabilitator** | `1788` | SOCKS5     | Реабилитатор через TURN-туннели          |
| **Opera-Proxy**        | `1888` | SOCKS5     | Проксирование через узлы Opera (socks-mode) |
| **FakeVPN**            | `1790` | SOCKS5     | Прямой локальный прокси с FakeTLS        |

---

> [!WARNING]
> **Отваливается интернет при включении режима VPN.**
> В таком случае может потребоваться выключить частный DNS (Private DNS - DoT / DoH) в системных настройках сети Android, так как системный VPN перехватывает DNS-запросы через внутренний резолвер `pdnsd`.

---

## Ссылки и используемые компоненты

*   [ЛМ (Tor)](https://torproject.org)
*   [zapret (bol-van)](https://github.com/bol-van/zapret)
*   [zapret-discord-youtube (flowseal)](https://github.com/flowseal/zapret-discord-youtube)
*   [Xray-core](https://github.com/XTLS/Xray-core)
*   [byedpi (hufrea)](https://github.com/hufrea/byedpi)
*   [byedpiandroid (dovecoteescapee)](https://github.com/dovecoteescapee/byedpiandroid)
*   [ByeByeDPI (romanvht)](https://github.com/romanvht/ByeByeDPI/)
*   [TGWS (Flowseal)](https://github.com/Flowseal/tg-ws-proxy)
*   [Socks-Reabilitator (AlexeyRF)](https://github.com/AlexeyRF/Socks-Reabilitator)
<!-- *   [vk-turn (cacggghp)](https://github.com/cacggghp/vk-turn-proxy) -->
<!-- *   [vk-turn-android (samosvalishe)](https://github.com/samosvalishe/turn-proxy-android) -->
*   [opera-proxy (Alexey71)](https://github.com/Alexey71/opera-proxy)

## Дисклеймер
В случае расследования какой-либо федеральной структуры или подобного, я не имею никакого отношения к этой группе или к людям в ней, я не знаю, как я здесь оказался, возможно, добавлен третьей стороной, я не поддерживаю никаких действий членов этой группы.

**Лицензия:** Все права принадлежат тем, кому они принадлежат.