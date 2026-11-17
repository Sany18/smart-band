# Mi Fitness 3.56.0 (China build) — аналіз і модифікація

Цільовий пристрій: Xiaomi Smart Band 9 Pro, китайська версія (`miwear.watch.n67cn`),
використовується в Україні. Застосунок: `com.mi.health` 3.56.0, витягнутий з власного
телефона (`apk/`, у git не входить). Декомпільований код (`work/`) теж не входить у git —
див. розділ «Що не публікується» в [головному README](../README.md).

Відтворити робоче середовище:

```bash
adb shell pm path com.mi.health            # -> base.apk
adb pull <path> apk/mi-fitness-3.56.0.apk
apktool d -o work/apktool apk/mi-fitness-3.56.0.apk
jadx -d work/jadx --no-res apk/mi-fitness-3.56.0.apk
```

## Підсумок

| Задача | Стан | Коротко |
|---|---|---|
| Замінити голосовий асистент | **працює на браслеті** (регіон акаунта — Китай) | хук у Mi Fitness + міст на Mac → Claude. Див. [`voice-hook/`](voice-hook/), [`../voice-bridge/`](../voice-bridge/), [`voice-assistant.md`](voice-assistant.md) |
| Встановлювати свої .rpk | реально, не зроблено | готовий прихований екран `ThirdAppDebugFragment` (вибір файлу → встановлення), потрібна лише точка входу |
| Прибрати китайську | частково реально | мова **браслета** береться з локалі телефона автоматично; у **застосунку** ~200 рядків у ресурсах і сотні в коді |
| NFC в Україні | **ні** | усі записи в secure element робить китайський сервер TSM, потрібні CN-акаунт і перевірка особи |

## Мова

- **Браслет:** у n67cn немає вибору мови. Mi Fitness сам шле на браслет локаль телефона
  (`DeviceSettingsSyncer.syncBluetoothLanguage()` → WearPacket type 2 / subtype 6,
  рядок на кшталт `en_us` / `uk_ua`) під час підключення і при зміні локалі.
  Чи покаже китайська прошивка `uk_ua` — вирішує прошивка, не застосунок.
- **Застосунок:** у `res/values/strings.xml` 207 рядків з китайськими ієрогліфами
  (~85 — місячний календар і подібне, решта — неперекладені `eco_device_*`,
  `training_plan_*`, `weight_*` тощо), плюс ~680 китайських літералів у коді
  (найбільше — `watch/face/edit/entity/WidgetTypeData.java`). Хости серверів зашиті
  на китайську хмару (`hlth.io.mi.com`), мова контенту йде параметром `locale`.
- Регіон береться з Mi-акаунта (`RegionExtKt.isInland()` = регіон `cn`), а не з локалі.

## Свої застосунки (.rpk)

- Конвеєр встановлення: `com.xiaomi.xms.wearable.ui.appshop.manager.AppInstallManager` →
  `DeviceModelExtKt.prepareInstallApp` (Command type 20 / subtype 1) → `sendFile(did, 4, …)`.
  Перед відправкою на браслет телефон **нічого не перевіряє**, крім розміру.
- Готовий інсталятор із вибором файлу: `com.xiaomi.xms.wearable.ui.debug.ThirdAppDebugFragment`
  (є й adb-режим з `packageName`/`filePath`/`versionCode`). Відкривається через
  `XmsManagerImpl.gotoDebugPage()` / `goDebugPageByAdb()`, але ніде не викликається.
- Найменший патч: експортована activity, яка викликає `goDebugPageByAdb(...)`.
- `market/watchsdk/debug/DebugTransmitActivity` — **не** інсталятор, лише тестові команди.

## NFC

Висновок: в Україні нереально, і патч APK цього не змінить.

- Видимість розділу карток: `NfcUtils.isShowCard()` — для CN-акаунта повний китайський
  набір; для решти — лише Mastercard/Visa, і то якщо браслет оголошує `MASTERCARD`
  (CN-версія цього не робить) і країна є в серверному білому списку (України там немає).
- Перепустки (MIFARE 13.56 МГц) — єдина категорія з правдоподібним сценарієм: зчитати
  незашифровану картку телефон може й локально, **але** записати її в secure element
  браслета можна лише APDU-скриптами від китайського сервера TSM. Для цього потрібні
  акаунт регіону `cn`, `CheckServiceAccess DOORCARD_PRO` і перевірка особи.
- Банківські й транспортні картки — лише китайська інфраструктура.

## Перепідписання

- Самоперевірка підпису в `MainComponet` спрацьовує лише на прошивках Xiaomi
  (на Samsung — ні) і показує тільки тост.
- `PermissionChecker` (`MiWearCoreService`) порівнює підписи — це має значення лише на
  HyperOS-телефонах із системним Lyra. Карти AMap, WeChat/Alipay можуть не працювати.
- Оригінал і мод підписані різними ключами: для встановлення оригінал треба видалити.
  Резервна копія зовнішньої теки — `work/backup-external-*`.
