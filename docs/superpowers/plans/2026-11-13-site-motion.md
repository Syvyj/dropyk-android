# Анімація сайту dropyk.syvyj.dev — план реалізації

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Оживити поточний сайт анімаціями на GSAP — приємний вхід, плавна поява блоків під час прокрутки й кілька інтерактивних моментів — не змінюючи вигляд, тексти й розкладку, які вже подобаються.

**Architecture:** Сайт статичний: `web/build.py` рендерить `web/src/page.html` у `web/index.html` і `web/uk/index.html`, стилі в `web/style.css`, деплой на Cloudflare через wrangler. Додаємо один файл `web/motion.js` і GSAP, покладений поруч у `web/vendor/` (без CDN і без збірника). Розмітка лише отримує атрибути `data-reveal` і кілька декоративних елементів; уся анімація — поступове покращення: без JS, з вимкненою анімацією в системі чи якщо скрипт не завантажився, сторінка виглядає точно як зараз.

**Tech Stack:** GSAP 3.15.0 (`gsap`, `ScrollTrigger`, `SplitText`, `DrawSVGPlugin`), чистий JS без модулів, Python 3 stdlib для збірки й перевірки.

**Spec:** запит автора (13.11.2026): «Мені зараз подобається той варіант сайту, що ми зробили. … план для того, щоб анімувати цей сайт: зробити його приємним, гарним; щоб всі блоки гарно переходили; щоб була якась інтерактивність для користувача … використав бібліотеку GSAP.» Сайт — `dropyk.syvyj.dev` з `web/` у цьому репо (`main` @ `e5394f1`).

## Global Constraints

- GSAP **3.15.0** (npm, 13.04.2026), файли з `gsap-3.15.0.tgz` → `dist/`: `gsap.min.js`, `ScrollTrigger.min.js`, `SplitText.min.js`, `DrawSVGPlugin.min.js`, без змін, із заголовком ліцензії. Кладуться в `web/vendor/gsap/3.15.0/`. Ліцензія — GSAP Standard «no charge» (https://gsap.com/standard-license).
- Жодних CDN і зовнішніх запитів: сайт обіцяє «без стеження», шрифти вже свої.
- Жодних нових залежностей, `package.json`, збірників. `web/motion.js` — звичайний скрипт із `defer`.
- Не змінювати тексти, кольори, розміри, порядок блоків і розкладку. Кожен новий елемент — `aria-hidden="true"`, декоративний.
- Анімуються лише `transform`, `opacity`, `clip-path` (виняток — тінь навігації). Кольори в JS не задаються: тема (світла/темна, кнопка й системна) працює через CSS-змінні.
- `prefers-reduced-motion: reduce` → жодного руху: усе видно одразу, у героя статичний екран «очікування».
- Інтерактив лише за наведенням (`(hover: hover) and (pointer: fine)`), на дотикових екранах його немає. Ширина ≤920px — без паралаксу й без лінії кроків.
- Обидві мови (`/` і `/uk/`) отримують однакову анімацію; SplitText ділить лише на слова (українські заголовки довші, на букви не ділимо).
- Тривалості й криві: поява блоків `duration: .8, ease: "power3.out", y: 28, stagger: .08`, старт тригера `"top 85%"`, `once: true`.

## Review Focus

1. **`motion.js` чи GSAP не завантажились** (блокувальник, повільна мережа): за 3 с сторінка має стати повністю видимою і такою лишитись — тест у Task 1.
2. **Клавіатура:** Tab по кнопках і посиланнях у ще не показаних блоках не має їх пропускати чи фокусувати невидимий елемент — тест у Task 2.
3. **Перехід за якорем / оновлення посеред сторінки** (`#install`, `#mac`, кнопка «Як встановити»): блоки, що вже над екраном і на екрані, видимі; швидка прокрутка донизу — нічого не лишилось прозорим — тест у Task 2.
4. **Системне «зменшити рух»:** усе видно, нічого не рухається, екран у героя не міняється — тест у Task 2 і Task 4.
5. **Телефон 375px:** немає горизонтальної прокрутки від стартових зсувів, немає нахилу й «магнітних» кнопок — тест у Task 3.

---

### Task 1: GSAP у проєкті, підключення й запобіжник

**Files:**
- Create: `web/vendor/gsap/3.15.0/{gsap,ScrollTrigger,SplitText,DrawSVGPlugin}.min.js`
- Create: `web/motion.js`
- Create: `web/check.py`
- Modify: `web/build.py` (значення шаблону), `web/src/page.html:17-35` (head), `web/style.css` (кінець файла), `web/.assetsignore`

**Interfaces:**
- Produces: клас `motion` на `<html>` — означає «анімація дозволена і ще не скасована»; `window.dropykMotion = true` ставить `motion.js`, коли береться до роботи; CSS-правило `.motion [data-reveal] { opacity: 0 }`; у `motion.js` — `const ready = gsap && document.documentElement.classList.contains('motion')`, усе інше в файлі працює лише при `ready`; шаблонне значення `{{mver}}` — перші 8 символів sha1 від `motion.js`.

- [ ] **Step 1: Write the failing check** — `web/check.py` (stdlib, запускається після `build.py`), функції-перевірки з `assert`:
  - `check_vendor()`: кожен із 4 файлів існує і перші 40 байтів містять `GSAP 3.15.0`;
  - `check_pages()`: в `index.html` і `uk/index.html` є `<script defer src="{root}vendor/gsap/3.15.0/X.min.js">` для всіх 4 файлів у порядку gsap → ScrollTrigger → SplitText → DrawSVGPlugin, а після них `<script defer src="{root}motion.js?v={sha1(motion.js)[:8]}">` (`root` = `""` / `"../"`);
  - `check_no_cdn()`: у зібраних сторінках і `motion.js` немає `cdn.`, `unpkg`, `jsdelivr`, `googleapis`;
  - `check_failsafe()`: у `<head>` обох сторінок є `classList.add('motion')`, `prefers-reduced-motion` і `setTimeout(` з `3000`.
- [ ] **Step 2:** `python3 web/build.py && python3 web/check.py` → FAIL на `check_vendor`.
- [ ] **Step 3:** покласти 4 файли з `npm pack gsap@3.15.0` (`package/dist/`); `build.py`: `{{mver}}` за тим самим зразком, що `{{ver}}` для CSS (винести в `ver(name) -> str`); у head після скрипта теми — запобіжник:
  ```js
  if (!matchMedia('(prefers-reduced-motion: reduce)').matches) {
    document.documentElement.classList.add('motion');
    setTimeout(() => { if (!window.dropykMotion) document.documentElement.classList.remove('motion'); }, 3000);
  }
  ```
  далі 5 тегів `<script defer>`; `motion.js` поки лише реєструє плагіни (`gsap.registerPlugin(ScrollTrigger, SplitText, DrawSVGPlugin)`) і ставить `window.dropykMotion = true`, якщо `ready`; у `.assetsignore` додати `check.py`.
- [ ] **Step 4:** `python3 web/build.py && python3 web/check.py` → PASS. Перегляд: `python3 -m http.server -d web 8090`, сторінка в браузері без помилок у консолі, на вигляд без змін; у DevTools заблокувати `motion.js` → через 3 с на `<html>` немає класу `motion` (Review Focus 1).
- [ ] **Step 5: Commit** `site: vendor GSAP 3.15.0, motion.js hook with a 3 s failsafe`.

### Task 2: Поява блоків під час прокрутки

**Files:**
- Modify: `web/src/page.html` (атрибути), `web/motion.js`, `web/check.py`

**Interfaces:**
- Consumes: `ready`, `.motion [data-reveal]` (Task 1).
- Produces: атрибут `data-reveal` зі значенням-групою. Анімації інших задач свої елементи `data-reveal` не позначають; для групи `mac` проявляється лише `.mac > div` (іконку анімує Task 6).

- [ ] **Step 1:** у `check.py` — `check_reveals()`: в обох сторінках рівно ці групи `data-reveal`: `strip`, `how-title`, `features-title`, `more`, `mac`, `install`, `private`, `support`; кожна ≥1 раз; на `<section class="hero">` і `.steps li` атрибута немає (ними займаються Task 3 і 5).
- [ ] **Step 2:** `python3 web/build.py && python3 web/check.py` → FAIL на `check_reveals`.
- [ ] **Step 3:** розставити `data-reveal` на контейнери цих блоків; у `motion.js` для кожного `[data-reveal]` — `ScrollTrigger` (`start: "top 85%"`, `once: true`), що проявляє його дочірні картки/пункти з глобальними значеннями (opacity 0→1, y 28→0, stagger .08); групи `install` і `private` — пункти по черзі. **Лише `opacity`, не `autoAlpha`**: невидимий елемент має лишатися фокусованим. Обробник `focusin` на `document`: якщо фокус у ще не показаному `[data-reveal]` — `tl.progress(1)`. Після `ScrollTrigger.refresh()` блоки, що вже вище екрана (перехід за якорем), одразу `progress(1)`.
- [ ] **Step 4:** `python3 web/build.py && python3 web/check.py` → PASS. У браузері (`http://localhost:8090/` і `/uk/`): прокрутити донизу, виконати в консолі
  ```js
  [...document.querySelectorAll('[data-reveal], [data-reveal] *')].filter(e => getComputedStyle(e).opacity !== '1').length
  ```
  → `0`. Відкрити `/#install` — блок «Встановлення» видно одразу. Від початку сторінки натискати Tab — кожен фокусований елемент видимий (Review Focus 2, 3). У DevTools «Emulate prefers-reduced-motion: reduce» → перезавантажити: класу `motion` немає, усе видно (Review Focus 4).
- [ ] **Step 5: Commit** `site: reveal blocks on scroll`.

### Task 3: Герой — вхід, паралакс, нахил пристроїв, рухомий фон

**Files:**
- Modify: `web/src/page.html:36-90`, `web/style.css`, `web/motion.js`

**Interfaces:**
- Consumes: `ready` (Task 1).
- Produces: елемент `<div class="blob" aria-hidden="true">` першим у `<body>`; клас `.devices-tilt` (обгортка всередині `.devices` навколо `.tv` і `.phone`, на ній нахил, щоб не конфліктувати з паралаксом на `.devices`).

- [ ] **Step 1:** перенести круг із `body { background: radial-gradient(…) }` у `.blob` (`position: absolute; top: 0; right: 0`; та сама позиція й розмір, `background: var(--blob)`, `border-radius: 50%`, `z-index: -1`) — без JS вигляд той самий; обгорнути `.tv` і `.phone` у `.devices-tilt`.
- [ ] **Step 2: Вхід** (таймлайн на `DOMContentLoaded`, лише при `ready`): `.pill` → заголовок `SplitText.create(h1, { type: "words", mask: "words", aria: "auto", autoSplit: true, onSplit: self => gsap.from(self.words, { yPercent: 110, stagger: .04 }) })` → `.lead` → `.cta` (y 16, stagger .08); паралельно `.tv` (y 40, scale .96) і `.phone` (x 40, y 30, rotation 4). Увесь вхід ≤1.3 с, ease `power3.out`.
- [ ] **Step 3: Паралакс** (через `gsap.matchMedia()`, лише `(min-width: 921px)`): на прокрутці героя `scrub: true` — `.devices` `yPercent: -6`, `.phone` додатково `yPercent: -12`, `.blob` `yPercent: 30`. Повільний дрейф `.blob`: `x/y ±2vw`, `scale 1.06`, `duration: 12`, `yoyo`, `repeat: -1`, `sine.inOut` (поза reduced-motion, на всіх ширинах).
- [ ] **Step 4: Нахил** (лише `(hover: hover) and (pointer: fine)`): `pointermove` над `.devices` → `gsap.quickTo` на `.devices-tilt` `rotationY`/`rotationX` до ±6°, `transformPerspective: 900`, `duration: .6`; `pointerleave` → 0.
- [ ] **Step 5:** `python3 web/build.py && python3 web/check.py` → PASS; у браузері: вхід грає раз при завантаженні, заголовок читається цілими словами в обох мовах; ширина 375px (пресет mobile) — `document.documentElement.scrollWidth === innerWidth` → `true`, нахилу немає (Review Focus 5); з вимкненим JS фоновий круг на місці.
- [ ] **Step 6: Commit** `site: hero entrance, parallax, device tilt, drifting blob`.

### Task 4: Герой — «історія передачі» на екрані телевізора

**Files:**
- Modify: `web/src/page.html:82-88`, `web/style.css`, `web/motion.js`, `web/check.py`

**Interfaces:**
- Consumes: `ready`; `.devices-tilt` (Task 3).
- Produces: у `.tv-screen` ще два `img.tv-alt` (`aria-hidden="true"`, `alt=""`, `decoding="async"`, `width="1440" height="810"`) з `{{lang}}-tv-receiving.webp` і `{{lang}}-tv-received-video.webp`, накладені на основний (`position: absolute; inset: 8px; opacity: 0`); `<svg class="flyer" aria-hidden="true"><use href="#drop"/></svg>` у `.devices`.

- [ ] **Step 1:** у `check.py` — `check_story()`: в `.tv-screen` обох сторінок рівно 2 `img.tv-alt`, з файлами своєї мови, і обидва файли існують у `web/assets/`.
- [ ] **Step 2:** `python3 web/build.py && python3 web/check.py` → FAIL на `check_story`.
- [ ] **Step 3:** розмітка й CSS із Interfaces; у `motion.js` — нескінченний таймлайн після входу героя: очікування 2.4 с → крапля `.flyer` летить дугою з екрана телефона в центр екрана ТВ (0.9 с; x лінійно, y через `keyframes` угору й униз, scale 0.6→1→0.4, opacity 0→1→0) → наплив на «приймаю» (0.4 с), тримати 2.2 с → наплив на «отримано», тримати 2.4 с → назад на «очікування» (0.6 с). Таймлайн на паузі, коли герой поза екраном (`ScrollTrigger` `onToggle`) і коли вкладка прихована (`visibilitychange`). При reduced-motion таймлайн не створюється.
- [ ] **Step 4:** `python3 web/build.py && python3 web/check.py` → PASS; у браузері: цикл іде, крапля летить із телефона в ТВ; прокрутити героя геть і назад — цикл продовжився з того ж місця; з «зменшити рух» — екран увесь час «очікування», крапля не з'являється (Review Focus 4).
- [ ] **Step 5: Commit** `site: hero story — a drop flies from the phone and the TV receives it`.

### Task 5: «Як це працює» — кроки, малювання іконок, лінія прогресу

**Files:**
- Modify: `web/src/page.html:98-117`, `web/style.css`, `web/motion.js`

**Interfaces:**
- Consumes: `ready` (Task 1).
- Produces: `<span class="steps-line" aria-hidden="true">` усередині `.steps` (лінія за картками, градієнт `var(--grad)`, `transform-origin: left`, видима лише ≥921px).

- [ ] **Step 1:** розмітка й CSS лінії (за картками, на рівні номерів, `scaleX(1)` без JS).
- [ ] **Step 2:** у `motion.js`: картки `.steps li` по черзі (глобальні значення, stagger .12); у кожній після появи — `DrawSVGPlugin`: усі `path, rect` з атрибутом `stroke` в `.icon` від `0%` до `100%`, `duration: 1`, `stagger: .05`; номер `.num` — `scale` 0.4→1, `back.out(2)`. На ≥921px лінія `scaleX` 0→1 зі `scrub: true` від `top 75%` до `bottom 60%` секції.
- [ ] **Step 3:** у браузері: іконки «малюються», після цього вигляд іконок як у статичній версії (порівняти зі знімком без JS); на 375px лінії немає; Review-перевірка з Task 2 (opacity) → `0`.
- [ ] **Step 4: Commit** `site: steps draw themselves, a progress line fills on scroll`.

### Task 6: «Що вміє» та інші картки — хореографія й дрібна інтерактивність

**Files:**
- Modify: `web/src/page.html:119-215`, `web/style.css`, `web/motion.js`

**Interfaces:**
- Consumes: `ready`, правило «свої елементи без `data-reveal`» (Task 2).

- [ ] **Step 1:** у `motion.js`, кожен `.feature` окремим тригером: картинка/картка виїжджає зі свого боку (`x: ±48`, разом із `clip-path: inset(0 0 0 100%)` → `inset(0)` для `img`), текст — глобальною появою з затримкою .15. Сторона — з того, ліворуч чи праворуч елемент у сітці (на ≤920px усе знизу вгору, `x: 0`).
- [ ] **Step 2:** `.qs-card` після появи: кнопка `.qs-buttons .on` один раз «натискається» (`scale` 1→.94→1, 0.35 с) і картка ледь підстрибує; `.where-card` — по черзі. Іконка Mac (`.mac img`) — поява (opacity, scale .9→1), далі плавне погойдування `yPercent: -5` (не `y`, щоб не сперечатися з появою), `duration: 3`, `yoyo`, `repeat: -1`, `sine.inOut`.
- [ ] **Step 3: Наведення** (лише `(hover: hover) and (pointer: fine)`): `.more-card`, `.private li`, `.install li`, `.give` — `y: -4` і тінь як у `.feature > img`, 0.25 с, назад при виході; великі `.btn` (не `.btn-small`) — «магніт»: `quickTo` x/y до 6px за курсором, `elastic.out(1, .4)` при виході. Фокус клавіатурою дає той самий підйом, що й наведення.
- [ ] **Step 4:** у браузері: перевірка opacity з Task 2 → `0`; на 375px немає підйомів/магніту; `scrollWidth === innerWidth` (Review Focus 5); темна тема — тіні й кольори карток як у статичній версії.
- [ ] **Step 5: Commit** `site: feature rows choreography, card hovers, magnetic buttons`.

### Task 7: Навігація — ховається при прокрутці, підсвічує розділ, крутить іконку теми

**Files:**
- Modify: `web/style.css`, `web/motion.js`

**Interfaces:**
- Consumes: `ready` (Task 1); посилання `.nav nav a[href="#how"]`, `a[href="#install"]` і `button.theme` з поточної розмітки.
- Produces: CSS-клас `.is-here` на посиланні навігації (колір `var(--accent)`), клас `.nav.scrolled` (тінь `0 8px 24px rgba(40, 36, 100, .10)`).

- [ ] **Step 1:** `ScrollTrigger.create({ start: 0, end: "max", onUpdate })`: після 120px прокрутки вниз навігація `yPercent: -100` (0.3 с), угору — `0`; не ховати, поки фокус усередині `.nav`; `.scrolled` після 8px.
- [ ] **Step 2:** для `#how` і `#install` — тригер `start: "top center", end: "bottom center"`, `onToggle` ставить/знімає `.is-here` на відповідному посиланні.
- [ ] **Step 3:** клік по `.theme` (наявний `onclick="flipTheme()"` лишається) — `svg` обертається на 180° (0.5 с, `power2.inOut`); при reduced-motion — без обертання.
- [ ] **Step 4:** у браузері: прокрутка вниз ховає меню, угору повертає; Tab по меню, коли воно сховане, — показує його; «Як це працює» підсвічено, коли розділ посередині екрана.
- [ ] **Step 5: Commit** `site: nav hides on scroll down, marks the current section, spins the theme icon`.

### Task 8: Підсумкова перевірка й викладення

**Files:**
- Modify: — (лише перевірка й деплой)

- [ ] **Step 1:** `python3 web/build.py && python3 web/check.py` → PASS.
- [ ] **Step 2:** матриця в браузері — `/` і `/uk/` × світла/темна × 1440px/375px × звичайний рух/reduced-motion: консоль без помилок; перевірка opacity з Task 2 → `0` після прокрутки донизу; немає горизонтальної прокрутки; з вимкненим JS — сторінка як до змін.
- [ ] **Step 3:** вага: `wc -c web/vendor/gsap/3.15.0/*.js web/motion.js` — разом ≤140 000 байтів.
- [ ] **Step 4:** показати автору локальну версію і **лише після його «так»** викласти: `cd web && python3 build.py && npx wrangler@4 deploy`; перевірити, що `https://dropyk.syvyj.dev/` і `/uk/` побайтно збігаються з `web/index.html` і `web/uk/index.html`.
- [ ] **Step 5: Commit** (якщо `build.py` щось переписав) і, за згодою автора, push на GitHub і Gitea.
