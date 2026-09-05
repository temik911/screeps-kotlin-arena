# Pain and Gain: что известно снаружи — сводка веб-поиска (05.09.2026)

Заказ оператора: «поискать в интернете стратегии для похожей задачи — возможно, мы зашли в тупик». Поиск делал
субагент по официальным материалам арены, Discord Screeps, GitHub, блогам игроков и теории RTS-микро; здесь — то, что
применимо к нашим правилам (14 фиксированных крипов, равная скорость, флаги с дебаффом владельцу, 2000 тиков,
потеря армии = поражение). Каждое утверждение — со ссылкой; чего не нашлось, сказано прямо.

## 1. Прямых разборов Pain and Gain нет

Арене четыре дня (Season 4 стартовал 01.09.2026); у анонса ноль комментариев —
https://store.steampowered.com/news/app/1137320/view/683012290458419415. Единственный источник по арене —
официальный тред в Discord (o4kapuk, 01.09.2026): победа — по счёту к концу, досрочно при недостижимом отрыве, либо
уничтожением всей армии; счёт из кода бота недоступен («will add it later»), けろびー считает его сам через
`ScoreFlag.scorePerTick`; реплеи со счётом — в клиенте ветки `preview` —
https://discord.com/channels/860665589738635336/1544105819635712051. Typings: `TICKS_LIMIT = 2000`,
`MAX_SCORE_PER_TICK = 25` — https://github.com/screeps/arena-definitions/tree/master/6a86d8c454a3948a1e35f90c/typings/season_4/pain_and_gain/basic.

В `#arena-talk` (04–05.09.2026) сильнейшими названы けろびー (Kero) и Hardy; игроки переносят построения из World —
quads («легко фокусить лечение на любом»), duos (лекарь позади атакера), snakes, blobs («нужен хороший код движения и
лечения»); дебаффы противника читаются с его крипов (`Effects: Attack modifier x0.8`) —
https://discord.com/channels/860665589738635336/866441789568974880. По истории матчей arukuka на низком рейтинге
матчи решаются аннигиляцией в первой стычке (314–319 тиков) — https://github.com/arukuka/screeps-arena-tools.

## 2. Флаговые арены прошлых сезонов — что задокументировано

**jonwinsley, CTF, 1000 → 1950.** Проигрыши — лучше скоординированным («clustering better, focusing healing and damage,
a couple were running quads») и оборонительным ботам. Правила входа в бой: у своей башни — всегда, вне — только при
перевесе 2:1, иначе ждать (1000 → 1600) — https://www.jonwinsley.com/notes/screeps-arena. Затем группа по центроиду,
лидер — ATTACK, отставшие подтягиваются; в бою ATTACK встаёт между врагом и флагом на клетки «с бо́льшим лечением и
меньшим входящим уроном», RANGED держит 3 и кайтит, HEAL идёт к раненым; цель — ближайший к центроиду группы,
предпочтительно уже атакуемый; «from a complete wipe to no losses», 1800 → 1950 —
https://www.jonwinsley.com/screeps/2022/05/03/screeps-arena-grouping-up/. На 1600 семь матчей из десяти — ничьи: обе
стороны ждут; выход — забирать нейтральное и делать поздний рывок —
https://www.jonwinsley.com/screeps/2022/04/18/screeps-arena-pressing-attack/. В Pain and Gain роль «ничьей» играет
гонка счёта: кто первым отказывается от боя, отдаёт флаги.

**qnz.one, CTF / Spawn & Swamp — единственный описанный адаптивный бот.** `StrategyEngine` «detects the hostile
strategy, picks a counter strategy and handles transitions» — https://qnz.one/2022/07/25/designing-a-screeps-arena-bot/;
детекция — таксономия по скорости и роли, контр — на самый частый тип; названный провал — тот же, что у нас: «the bot
loses battles even if it has a clear advantage … it lets hostiles retreat and heal up» —
https://qnz.one/2022/08/18/rock-paper-scissors/; чинили излишнюю осторожность (+110 рейтинга) и защиту, которая
«stuck trying to chase» — https://qnz.one/2022/08/12/actual-gameplay/, https://qnz.one/2022/08/24/rating-stabilization/.

## 3. Ровный бой мили + стрелки + лекари при равной скорости

**Выбор цели.** Прямого ответа в источниках по Arena нет; три опоры: Seneschal (World) — «rangedMassAttack … the
key for the healers as they can only heal one unit at a time», один атакер «can't overpower three healers» —
https://screeps.com/forum/topic/327/keeper-lairs-and-invader-swarms-strategy; Overmind (топ World) без «лекари первыми»:
цель по `hitsMax − hitsPredicted + healPotential` — добивать того, кого проще снять с учётом лечения —
https://github.com/bencbartlett/Overmind/blob/master/src/targeting/CombatTargeting.ts; теория — закон Ланчестера
(сила ∝ N² при фокусе) и эвристика «цель с наибольшим DPS/HP» в исследованиях боевого ИИ —
https://www.cse.unr.edu/~simingl/papers/PlayerModeling/Fast%20Heuristic%20Search%20for%20RTS%20Game%20Combat%20Scenarios.pdf.
Перенос на наши правила (вывод, не источник): части ломаются спереди —
https://wiki.screepspl.us/index.php/Combat — значит «HP» цели — хиты до обезоруживания (мили 800, стрелок и лекарь
600), а «DPS» — только то, что достаёт нас *сейчас*: мили в трёх клетках — 0/800, стрелок в бою — 60/600, лекарь —
72/600. Соперник-блок делает против нас ровно это. Правило: `score = (текущая угроза + лечение) / хиты до
обезоруживания` с бонусом «уже под фокусом».

**rangedMassAttack.** 10/4/1 на дистанциях 1/2/3 —
https://steamcommunity.com/app/464350/discussions/0/1743386608824190102/; бьёт всех врагов в трёх клетках, своих не
задевает, несовместим в один тик с `rangedAttack`/`rangedHeal` —
https://github.com/RafeSymonds/Screeps-Arena/blob/main/docs/api/Creep.md. На 6 частях — 60 каждому вплотную:
окупается при ≥2 врагах рядом; ценность — размазать урон так, чтобы три лекаря не успевали.

**Лечение и остовы.** `heal`/`rangedHeal` восстанавливают функцию частей; урон и лечение в одном тике
суммируются (`newHits = hits + heal − damage`), пре-хил — стандарт штурма —
https://screeps.com/forum/topic/2483/simultaneous-actions-clarification; порядок интентов — по «снимку» состояния тика,
не по возрасту крипа — https://screeps.com/forum/topic/628/documentation-request-order-of-execution-of-different-actions,
https://screeps.com/forum/topic/1122/creep-age-affects-order-of-operations. Для Arena точный порядок не
документирован; его меряет реплика сервера Bacha сравнением реплеев —
https://github.com/BachaBajceps/bacha-dev-website/blob/main/src/content/blog/official-screeps-arena-server-replica.md.

**Построение.** bonzAI (турниры World): «Longbow» — стрелки чередуют шаг вперёд-назад и «incredibly effective even
for very well healed melee+healer squads»; отход ведёт лекарь; и главное против фокуса — «you can distract creeps away
from your main target, depending on how the defending creeps choose their targets» —
https://github.com/bonzaiferroni/bonzAI/wiki/Screeps-Warfare-Championships:-Gifs-and-Gaffes. Правило: дешёвая
приманка (скаут, 100 хитов) на дистанцию 3 за тик до контакта — если их фокус «ближайший», залп 5×60 сгорает на 100.

## 4. Кайтер при равной скорости

Tigga: «Kiting can go wrong awfully quickly around swamps or room edges» —
https://screeps.com/forum/topic/2319/automatic-hit-back-after-attack; fatigue 10 за не-MOVE часть на болоте, MOVE снимает
2/тик — https://docs.screeps.com/creeps.html — при телах 1:1 это пять тиков на клетку у обеих сторон, поэтому болото —
ловушка для того, кому надо через него *пройти*. Общая теория: окружение «negates any sort of retreat» —
https://liquipedia.net/starcraft2/Micro_(StarCraft); погоня — известный провал (qnz), jonwinsley решал перехватом —
армия «between the enemies and our flag». Перенос: остаток обязан *остановиться на флаге*, чтобы его взять, — единственный
момент поимки; армия стоит между остатком и большинством флагов и выходит на захват двумя группами с разных сторон; а
брать флаги могут *любые* крипы — два скаута и разоружённые остовы с целыми MOVE — капперы, которых остатку из четырёх
не перекрыть.

## 5. Чтение соперника в первые тики

Опубликованных детекторов два: таксономия qnz.one и геометрия jonwinsley («if the enemies are spread out, hang back …
if they are close together, move in for the kill»). Для Pain and Gain этого хватает на три режима: RUSH (плотный блок,
вектор на нас, ни одного флага), GRAB (разброс, флаги переворачиваются), KITE (остаток после боя уходит, флаги
перезахватываются). Плюс читать `effects` вражеских крипов и пересчитывать паритет с их дебаффами: держатель 4+ флагов
слабее на 20–30 % *прямо сейчас*.

## 6. Инструменты

- **arukuka/screeps-arena-tools** — тянет из залогиненного клиента полные реплеи (потиковые снимки; viewer показывает
  «attacks/heals, energy, and flag captures»), есть `sync "Pain and Gain"`, macOS —
  https://github.com/arukuka/screeps-arena-tools. Прямое продолжение нашего `tools/match-log.py`: не консоль, а
  движение и цели противника по тикам. Это закрывает главный разрыв стенда — чужих интентов в наших логах нет.
- **wtfrank/screeps_arena_sim** — Rust-симулятор с импортом карт через mitmproxy — https://github.com/wtfrank/screeps_arena_sim.
- Клиент ветки `preview` показывает счёт в реплеях.

Не нашлось: видео и стримов по Pain and Gain, кода под `arena/season_4/pain_and_gain` кроме typings, тредов Reddit по
бою в Arena, тактических гайдов Steam.

## Что пробовать первым (оценка субагента, ранжировано по подкреплённости)

1. Целеуказание «угроза / хиты до обезоруживания» и `rangedMassAttack` по лекарям у фронта при ≥2 врагах рядом.
2. Против кайтера — не догонять, а считать флаго-тики: капперы из скаутов и остовов, перехват между остатком и
   большинством флагов, атака только когда остаток стоит на флаге, двумя группами.
3. Приманка-скаут за тик до контакта.
4. Паритет с учётом `effects` противника и детектор RUSH/GRAB/KITE по первым тикам.
5. Снять реплеи Kero и Hardy через arukuka/screeps-arena-tools и превратить их построение и выбор цели в сценарии
   стенда — пока арене четыре дня, «мета» — это ровно то, что делают эти двое.
