# Privacy policy — Background Camera

*Last updated: 11 October 2026 · [Русская версия ниже](#политика-конфиденциальности--background-camera)*

Background Camera (`io.github.holego.bcam`) is a video recorder for Android developed by
**Holego**. This policy covers every version of the app: the one on Google Play, and the one
on F-Droid and GitHub.

## In short

* Your recordings, and the app's settings, **stay on your device**. The app never uploads
  them anywhere, and the developer has no server that could receive them.
* The app has **no account, no analytics and no crash reporting of its own.**
* The **F-Droid / GitHub version has no network access at all** and no ads.
* The **Google Play version shows ads (Google AdMob)** and offers an optional subscription
  that turns them off. Those two features use the internet and are the only reason this policy
  has more than a few lines.

## What stays on your device

| Data | Where it lives | Leaves the device? |
|---|---|---|
| Videos and, if you enable it, their audio | The folder you pick (Android Storage Access Framework) | **No** |
| Settings (folder, camera, quality, language…) | The app's private storage | **No** |

Removing the app deletes the settings. The videos are ordinary files in your folder; the app
never deletes them, and you manage them yourself.

## Google Play version: ads

The free Google Play version shows ads provided by **Google AdMob** (Google LLC). To choose,
deliver and measure ads, and to prevent fraud, the Google Mobile Ads SDK in the app may
collect and use, according to the consent you give:

* the **advertising ID** of your device (you can reset or delete it in Android settings →
  Privacy → Ads);
* your **IP address** and the **approximate location** derived from it;
* device and app information (model, OS version, language, app version);
* how you interact with ads (impressions, taps), and crash/diagnostic data from the SDK.

This data is processed by Google under [Google's privacy policy](https://policies.google.com/privacy);
see also [how Google uses data from apps that use its services](https://policies.google.com/technologies/partner-sites).
The developer does not receive this data, only aggregated ad revenue reports.

**Consent.** If you are in the European Economic Area, the United Kingdom or Switzerland, the
app first shows Google's consent message and only requests ads according to your choice. You
can review or change that choice at any time under **Ad privacy settings** on the main screen.
Elsewhere you can still reset your advertising ID in Android settings.

Ads are shown only on the setup screen, at natural pauses. **They are never shown while a
recording is running, and never when you start or stop a recording from the widget or the
notification.**

**Not for children.** The app is not directed to children under 13 (or under the applicable
age of digital consent) and does not knowingly serve ads to them.

## Google Play version: the ad-free subscription

The subscription is an auto-renewing subscription sold and handled entirely by **Google Play
Billing**: the price, the renewal period and the terms are shown to you before you subscribe,
and you can change or cancel it at any time in Google Play under *Subscriptions*. The
developer never sees your payment details. The app only asks Google Play whether your
subscription is active, and keeps a local flag so it can hide ads. While it is active the ad
SDK is not started at all. The subscription is restored on a new device through the same
Google account.

Optionally, you can watch a short rewarded video to turn ads off for 24 hours; the time of
that reward is stored on the device only.

## Permissions

| Permission | Why |
|---|---|
| Camera | To record video. |
| Microphone | Only if you turn on *Record audio*. |
| Foreground service (camera, microphone) | Keeps a recording running with the screen off; it always shows a notification with a Stop button. |
| Notifications | To show that a recording is in progress. |
| Wake lock | So a recording is not cut off when the screen turns off. |
| Internet, network state, advertising ID *(Google Play version only)* | Ads and the purchase. Not present in the F-Droid / GitHub version. |

## Responsible use

Recording people may require their consent and is regulated by law in many places. You are
responsible for using the app lawfully. While a recording runs the app always shows a
notification, and Android itself shows camera and microphone indicators.

## Your rights and contact

Because the developer does not hold personal data about you, there is nothing to access or
erase on the developer's side. For ad-related data, use **Ad privacy settings** in the app,
your Android advertising settings, and your Google account's
[Ad settings](https://adssettings.google.com).

Questions: open an issue at <https://github.com/Holego/BCam/issues> or write to the Jabber
address `Jopirat@jabber.fr`.

## Changes

If this policy changes, the new version will appear at this address with a new date.

---

# Политика конфиденциальности — Background Camera

*Обновлено: 11 октября 2026*

Background Camera (`io.github.holego.bcam`) — приложение для записи видео на Android,
разработчик — **Holego**. Политика относится ко всем версиям приложения: из Google Play и из
F-Droid / GitHub.

## Коротко

* Ваши записи и настройки приложения **остаются на вашем устройстве**. Приложение никуда их не
  отправляет, а у разработчика нет сервера, который мог бы их принять.
* **Нет аккаунта, нет собственной аналитики и отчётов о сбоях.**
* **Версия из F-Droid / GitHub вообще не имеет доступа к сети** и не содержит рекламы.
* **Версия из Google Play показывает рекламу (Google AdMob)** и предлагает необязательную
  подписку, которая её отключает. Только эти две функции используют интернет.

## Что остаётся на устройстве

| Данные | Где хранятся | Покидают устройство? |
|---|---|---|
| Видео и, если включено, звук | Выбранная вами папка (Storage Access Framework) | **Нет** |
| Настройки (папка, камера, качество, язык…) | Приватное хранилище приложения | **Нет** |

При удалении приложения настройки удаляются. Видео — обычные файлы в вашей папке: приложение их
не удаляет, вы управляете ими сами.

## Версия Google Play: реклама

Бесплатная версия в Google Play показывает рекламу **Google AdMob** (Google LLC). Чтобы
подбирать, показывать и учитывать рекламу и предотвращать мошенничество, Google Mobile Ads SDK
может собирать и использовать — в соответствии с вашим согласием:

* **рекламный идентификатор** устройства (сбросить или удалить его можно в настройках Android →
  Конфиденциальность → Реклама);
* **IP-адрес** и определяемое по нему **приблизительное местоположение**;
* сведения об устройстве и приложении (модель, версия ОС, язык, версия приложения);
* взаимодействие с рекламой (показы, нажатия) и диагностические данные SDK.

Эти данные обрабатывает Google в соответствии с
[политикой конфиденциальности Google](https://policies.google.com/privacy). Разработчик эти
данные не получает — только сводные отчёты о доходе от рекламы.

**Согласие.** Если вы находитесь в Европейской экономической зоне, Великобритании или
Швейцарии, приложение сначала показывает сообщение о согласии Google и запрашивает рекламу
только в соответствии с вашим выбором. Изменить выбор можно в любой момент: пункт **«Настройки
конфиденциальности рекламы»** на главном экране. В других регионах рекламный идентификатор можно
сбросить в настройках Android.

Реклама показывается только на экране настроек, в естественных паузах. **Её никогда нет во
время записи, а также при запуске и остановке записи через виджет или уведомление.**

**Не для детей.** Приложение не предназначено для детей младше 13 лет (или младше возраста
цифрового согласия, установленного законом) и сознательно не показывает им рекламу.

## Версия Google Play: подписка без рекламы

Это подписка с автопродлением, которую продаёт и полностью обрабатывает **Google Play
Billing**: цена, период продления и условия показываются до оформления, а изменить или отменить
подписку можно в любой момент в Google Play, раздел «Подписки». Разработчик не видит ваши
платёжные данные. Приложение лишь спрашивает у Google Play, активна ли подписка, и хранит
локальную отметку, чтобы скрывать рекламу. Пока подписка активна, рекламный SDK вообще не
запускается. На новом устройстве подписка восстанавливается через тот же аккаунт Google.

Дополнительно можно посмотреть короткое видео и отключить рекламу на 24 часа; время такой
награды хранится только на устройстве.

## Разрешения

| Разрешение | Зачем |
|---|---|
| Камера | Запись видео. |
| Микрофон | Только если включён пункт «Записывать звук». |
| Служба переднего плана (камера, микрофон) | Запись продолжается при выключенном экране; при этом всегда показано уведомление с кнопкой «Стоп». |
| Уведомления | Показать, что идёт запись. |
| Wake lock | Чтобы запись не прерывалась при выключении экрана. |
| Интернет, состояние сети, рекламный идентификатор *(только версия Google Play)* | Реклама и покупка. В версии F-Droid / GitHub их нет. |

## Ответственное использование

Запись людей может требовать их согласия и регулируется законом во многих странах. Вы сами
отвечаете за законное использование приложения. Во время записи приложение всегда показывает
уведомление, а Android — индикаторы камеры и микрофона.

## Ваши права и связь

У разработчика нет ваших персональных данных, поэтому на его стороне нечего запрашивать или
удалять. Для данных, связанных с рекламой, используйте **«Настройки конфиденциальности
рекламы»** в приложении, настройки рекламы Android и
[настройки рекламы](https://adssettings.google.com) аккаунта Google.

Вопросы: <https://github.com/Holego/BCam/issues> или Jabber `Jopirat@jabber.fr`.

## Изменения

При изменении политики новая версия появится по этому адресу с новой датой.
