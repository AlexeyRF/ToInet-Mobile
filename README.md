# ToInet-Mobile
**Версия для ПК:** [ToInet](https://github.com/AlexeyRF/ToInet)

**ToInet-Mobile** - приложение для Android, объединяющее в себе инструменты обхода блокировок: **zDPI** (гибридный ByeDPI + Zapret2 со стратегиями и пресетами General/YouTube/Discord), **Tor (ЛМ)**, **Opera Proxy**, **Easy-VPN**. 

## Основные возможности
*   **Easy-VPN**:
    *   Программа для использования минимизации влияния свинолётов на вашу интернет жизнь.
*   **zDPI (ByeDPI + Zapret2)**:
    *   Гибридный движок ByeDPI с полной интеграцией Zapret2 (desync, split, disorder, fake, tlsrec, quic-drop, midsld), скомпилированный под Android.
    *   Встроенный тестер стратегий для подбора оптимальных параметров zDPI / Zapret / ByeDpi.
*   **Режим системного VPN**:
    *   Раздельное туннелирование, можно маршрутизировать трафик отдельных приложений
*   **Tor (ЛМ)**:
    *   Встроенный луковый маршрутизатор.
*   **Socks-Reabilitator**:
    *   Прогон SOCKS5-прокси через zDPI, доступен так же режим без десинка.
*   **FakeVPN**:
    *   Прямое туннелирование с опциональной подменой SNI (FakeTLS).
*   **Opera Proxy**:
    *   Маршрутизация через прокси-серверы Opera.
*   **SVPN (SSH)**:
    *   SSH-туннелирование с динамическим SOCKS5-прокси (JSch) и поддержкой системного VPN.
    *   Гибкая настройка: хост, порт, логин, пароль или приватный SSH-ключ (PEM / OpenSSH / Ed25519) и upstream DNS.

---

## Порты по умолчанию

Если вам необходимо добавить в приложение прокси, то ToInet-Mobile используют фиксированные локальные порты (синхронизированы с ToInet-MAX):

| Инструмент             | Порт   | Тип прокси | 
|:-----------------------|:-------|:-----------|
| **zDPI (ByeDPI)**      | `1780` | SOCKS5     |
| **Zapret (zDPI)**      | `1781` | SOCKS5     | 
| **Tor Socks5**         | `9853` | SOCKS5     |
| **Tor HTTP**           | `5267` | HTTP       |
| **Opera-Proxy**        | `1785` | SOCKS5     | 
| **Easy-VPN (Xray)**    | `1790` | SOCKS5     |
| **FakeVPN (VLESS)**    | `1790` | SOCKS5     |
| **Socks-Reabilitator** | `1788` | SOCKS5     |
| **SVPN (SSH)**         | `1770` | SOCKS5     |
| **TGWS (Proxy)**       | `1480` | SOCKS5     |

---

> [!WARNING]
> **Отваливается интернет при включении режима VPN.**
> В таком случае может потребоваться выключить частный DNS (Private DNS - DoT / DoH) в системных настройках сети Android, так как системный VPN перехватывает DNS-запросы через внутренний резольвер `pdnsd`.

---

## Авторы и используемые компоненты

*   [ЛМ (Tor)](https://torproject.org)
*   [Xray-core](https://github.com/XTLS/Xray-core)
*   [zDPI (ByeDPI + Zapret2 hybrid engine)](https://github.com/hufrea/byedpi)
*   [hufrea (Оригинальный ByeDPI)](https://github.com/hufrea/)
*   [dovecoteescapee (Оригинальный ByeDPI для Android)](https://github.com/dovecoteescapee/)
*   [bol-van (Zapret)](https://github.com/bol-van/)
*   [romanvht (Стратегии и тестер ByeDPI)](https://github.com/romanvht/)
*   [Flowseal (Оригинальный TGWS, стратегии Zapret-discord-youtube)](https://github.com/Flowseal/)
*   [Alexey71 (OperaProxy)](https://github.com/Alexey71/)
*   [mwiede (JSch - SSH для SVPN)](https://github.com/mwiede/jsch)


## Дисклеймер
В случае расследования какой-либо федеральной структуры или подобного, я не имею никакого отношения к этой группе или к людям в ней, я не знаю, как я здесь оказался, возможно, добавлен третьей стороной, я не поддерживаю никаких действий членов этой группы.

**Лицензия:** Все права принадлежат тем, кому они принадлежат.
