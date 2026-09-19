# Pain and Gain — история версий, эпоха v1xx (v100–v180)

Часть `docs/pain-and-gain.md`: один абзац на версию или живой матч, в порядке записи. Вынесено из главного файла 20.09.2026
(`docs/pain-and-gain-architecture-2.md`, этап 7) без правки текста; оглавление эпох — в главном файле. Искать по имени —
`python3 tools/know.py <тег строки | факт | константа>`.

**v100 (06.09.2026, the operator's decision: the scatter on the stand and the opening without the corner).** Two things. The stand gets `scatter`, the match-240 scatterer built from the replay (see the README): on the six maps of the spread family it reproduces the live opening to the tick (his D5 at 38, R3 at 43, both A3 at 53/56, both H4 at 82; ours R3 at 42–49) and v99 wins all six only just — map 31 by 217, map 34 by 1 456, map 30 by 1 469, with the live shape inside (D5 ours at ~130, parity at 3339:3098, RETREAT at 400 at 3044:3554, −6 800 at t=700, the lead only at t=1992); the six lines join the gate. On the way a stand defect: the farmer/camp blob headed for the nearest flag that was not its own even with our creep standing on it, and with our army posted next to its nearest flag it danced there all match instead of farming the rest and parking — the post is now the nearest takeable flag (alone: gate 125/125 at 4 worse / 4 better, all camp and farm lines). The bot: USE_OPENING_AT_POST — the unflagged-rush signal stays whole (the first flag is theirs, hunted, the exit at objectives), but the EVADE it used to force is gone: the army stands at the post (the flag of our half, v88) with the scout on it and leaves only by the ordinary evade (an armed enemy within EVADE_RANGE). The first form, a signal range of fifty cells, was tried and dropped: a scatter's fan enters fifty cells by t=17 while 'massed' still holds, and the army evaded from t=17 to 36 into the same corner (scatter map 34). Measured apart from the stand fix: gate 125/125 at 29 worse / 51 better — the openings of every scenario change; in the worse column nine 'destroyed -> lead' lines (twelve 12, camp 29/30, army 31, block 32, kite 34, wing 34, fourteen 35, rush 5) and the screen+flagless margins (maps 25 and 30 11 800 -> 1 832), in the better column fifty-one lines of enemy armies destroyed sooner (army 3/5/21/23/24/29/30/34/35, nine 28/29/32/33, rush 28/29/33/34, kite, roost, hunter). camp+shy five of five, spread five of six (map 33 lost, map 19 won), scatter six of six by 3 700–8 300 (map 31 24 324:17 751 against 24 318:24 101, map 34 24 320:19 683 against 24 314:22 858). The live number is the next opening. (The code, the stand form and the README landed as 9a786ea; the gate lines and this paragraph followed — the landing script's docs step failed on an anchor and the commit went out without them.)

**Matches 244–249 (06.09.2026, the first series of v100): 5:1, the best series of the day (1062 -> 1088 -> 1079).** けろびー in 1 000, G1N6ERbreadMan in 1 100, けろびー in 100, System in 1 500, then ricardo18informatica2020 — the scatterer of match 240 the stand scenario was built from — in 1 700 (1080 -> 1088); and then けろびー as a blob, the form of match 238, annihilated in 300 (-> 1079). The opening at the post worked as designed in all six: no corner, the scout on our R3 from the first flag, the army where the game is. Match 249 was the blob brawl again, at the centre instead of the open: HOLD to the post from t=3, the ordinary evade at 27 (an armed of his within 25), HOLD at 32 when the approach rate fell to zero, contact at 43 at 4087:4087, ANNIHILATE by contact. We killed three of his melee by t=100 and the model had us ahead (1832:1350) — and we were wiped by t=160 at his 11 200/16 000. The replay (t=43–160): his 412 shots and 51 swings against our 277 and 15; his melee adjacent to an enemy 20 % of creep-ticks, ours 5 %; his healers adjacent 235 times, ours 178; our ranged disarmed 113 creep-ticks, his 42, with the same focus (2.08 targets a tick, share 0.66–0.68). The trace of the first ten ticks: our melee_4 stepped two cells ahead alone at t=44 and was focused from 1 600 to nothing by t=52 while the other three stood at three or four under his ranged (holdMelee); the press fired at 49, two went in, swung three or four times each and were focused; his fourth melee walked round the flank and swung at our ranged and healers eight ticks running (a->ranged 21, a->healer 15 over the fight; ours a->melee 11, healed by three adjacent healers). The give-up rule of v96 marked all twelve of his creeps at t=46–49 — his blob did step back a cell at first contact — and our melee could not take them for twenty ticks. This is the fight-quality item (Coldkimchi, matches 238 and 249): the blob brawl at parity is lost on mechanics the day's rules do not touch — melee that find soft targets, healers that stay adjacent, ranged that live armed — and the stand's rush/nine/block enemies are beaten by the same mechanics, so it cannot measure a fix. Open, and now the largest item.

**v101 (06.09.2026, the operator's list in order — 1: the blob brawl on the stand; 2: fight quality, first item).** The stand gets `brawl` (see the harness section), built from the replays of 238 and 249 in four iterations: a blob that dives through our line is shredded by mass fire in thirty ticks (twelve deaths t=94–124 on map 31); one that keeps its edge — formed advance, melee at the front, ranged three from our nearest armed and three from every other, healers behind the ranged, a three-tick step back at first contact — fights for 280–540 ticks and still dies on all eight maps. Before tuning further the engine was checked against the replays: the damage and healing the intents should have produced (10 per live RANGED part a shot, 30 per ATTACK part a swing, 12 per HEAL part adjacent) against the hits actually lost and regained — match 249: his 34 260 expected, 23 230 observed lost; ours 19 500 against 13 973; match 238 the same within overkill and overlapping heals. The stand's engine is faithful; the loss is in the intents, and it decomposes: his 361 shots and 51 swings against our 262 and 15, his 235 adjacent heals against our 178. Uptime by role (the new `ours act:` line on the stand, the same count from the replay live): our healers heal in 94 % of the ticks they stand next to a wounded mate (his 100 %), our ranged fire in 70 % of the ticks with a target in three (his 89 %; the rest disarmed), our melee swing in 83 % of their adjacent ticks (his 86 %) — but are adjacent only 18 creep-ticks against his 59 (5 % against 20 %). Melee adjacency is the gap, and the stand with the feint reproduces it (5–16 %). First item of the fight list, the lone melee two ahead of the mass (v31; melee_4 of match 249 dead in eight ticks): three forms measured. A gate alone — a melee takes a target at two only with a mate within three of it (v101a): gate 130/131 (camp 30 23 283:23 793) at 12 worse / 9 better, brawl adjacency unchanged. The gate with a join (v101b): 130/131 at 16 worse / 15 better — in both, camp 34, rush 34 and nine 31 go from destroyed to a points lead: the stand's blobs are beaten by single melee entries and holding them back gives the blobs away. The join alone, USE_MELEE_PAIR_ENGAGE: a melee whose mate already stands at two from a target reaches it from three, so two arrive together — gate 131/131 (the six scatter lines included) at 5 worse / 8 better (camp 29 margin 21 066:11 059 -> 23 808:23 369, wing 33 to a lead; nine 19, wing 29/30/35, fourteen 35, block 32 to destroyed), brawl adjacency up to 20 % on map 31, camp+shy five of five and spread five of six unchanged. The gate half stays in the code as USE_MELEE_PAIR_GATE = false.

**v102 (06.09.2026, the operator's list in order — 3: the centre flag; 5: the price of ranged fire; 7: the closed findings).** Item 3, USE_POST_ON_CENTRE: while D5 is ours the army's post is D5 itself, not the centroid of our flags — in match 243 the runner took D5 at 71, the army stood at the centroid (64,40) fourteen cells away, and his blob of twelve walked onto D5 at 118 and sat there to 1312 at a parity we never contested; in 242 he took it himself and parked beside it. Measured in the plan worktree on top of v101: gate 131/131 at 8 worse / 4 better (camp 29 and 30 from a lead to destroyed, camp 33 and 34 the other way, farm+weak 28 to a lead, scatter 30/33 margins down and 31 up), camp+shy five of five, spread five of six, scatter six of six, brawl unchanged. A doctrinal trial; the live number is the next sentinel. Item 5, USE_MELEE_ADJACENCY_SHARE, measured and rejected: the power model counts a melee's 240 a swing in full (four M8A8 are three quarters of a side's damage in it) where the replays of 238/249 have melee adjacent 20 % of creep-ticks for him and 5–8 % for us — 36 % of his damage and 19 % of ours — so R3 (−20 % ranged) costs the model 1 % of power and A3 7.5 %, the reverse of the fight. A share of 0.25 on the melee's damage (hits in full) passes the gate 131/131 but reshuffles everything (27 worse / 40 better) and collapses the races: spread six of six -> two of six (map 31 11 058:24 336), camp+shy five of five -> three of five, brawl map 32 our army destroyed. The flag prices flip (R3 from cheap to dear) under parity floors tuned for the old prices, and the captures the stand's races stand on are refused. The price is right by the replays and cannot be changed apart from the floors — an open finding with a number: 0.25 needs PARITY_FLOOR/CAPTURE_FLOOR retuned across every family. Item 4 (the shy blob) has no new code: v98's stall is the live test. Item 7: closed for good — the sleeper chase at 0.65 power (v12, before the parity doctrine), spread on maps 18/19/22/23 (five of six today, the lost map alternating), the corner opening (v100), the fruitless chase (the stall nets, v36–v98), the scatter core (v97), the keeps-distance window (v98).

**Matches 250–256 (06.09.2026, the series of v101 and v102): 1:1 and 4:1 (1079 -> 1083 -> 1074 -> 1088 -> 1080).** v101: ricardo18informatica2020 in 1 800 (the scatterer a second time), then けろびー's blob a third time in 200 — annihilated, and the replay narrows the fight-quality item to one number: shots 254 against 272, our healing larger (15 984 against 12 960 expected), the whole damage gap in 37 melee swings (8 880 of 9 960) — his melee adjacent 61 creep-ticks, ours 20, with our melee at two from an enemy for 117 creep-ticks and not stepping; 67 press give-ups in the fight, the jittering blob (a step back and forward at two) marked 'keeping its distance' every three ticks and refused to the engage. v102: あぶらむし twice (0 and 1 100 ticks), then MetalicaX in 1 600 — the second win over him ever (1074 -> 1085), the first with the army posted on D5 — and ricardo in 1 600, then ricardo in 1 700 lost on points.

**v104 (06.09.2026, the fight-quality item, second rule): a give-up only for a target beyond reach.** USE_GIVEUP_BEYOND_REACH: the press give-up ('keeps its distance', v30/v96) is set only for a target standing beyond MELEE_HOLD_RANGE — a target at two is hit next tick and keeps no distance in any sense that matters; the stand's screen (m35), which steps back to three, is marked as before. Measured in the plan worktree on top of v102: gate 131/131 at 4 worse / 1 better (farm+weak 32 margin −9 486, farm+weak 33, fourteen 33 and kite 4 slower; wing 34 +1 200), camp+shy, spread and scatter identical, brawl adjacency 6–20 % as before — the stand's blob does not jitter the way the live one does, so the rule barely fires there; the live number is the next blob.

**Matches 257–258 (06.09.2026, the first series of v104): 1:1 — G1N6ERbreadMan in 1 000 (1080 -> 1084), then MetalicaX touring in 1 700, 14 722:20 189 with our army whole and his at 14 400 (-> 1078).** The form of match 241: the push at 1.14 (3679:3232) followed his blob along the east side from t=300 to 1300 at nine to fourteen cells, reach 0/5, one of his dead at t≈250 and nothing after; huntable flickered to 0/12 and the posture to HOLD at 500, 600, 800; from 1400 HOLD at 4075:2925 with huntable 0. Not one stall line in 1 700 ticks: the keeps-distance window of v98 never fired through four hundred ticks of ANNIHILATE at a distance that never shrank — the next thing to read. Match 256 before it (v102's loss to ricardo) had the sentinel's other half: he held four flags with eight armed at D5 thirteen cells from our army, we three at 3436:3507, and the army held for 1 350 ticks — the D5 and H4 objectives passed the lost-race floor and the farmer's pack exemption and were cancelled by holdLine ('enemy near — a line, not an objective') 614 times, against a farmer that fired no shot in 1 700 ticks.

**v105 (06.09.2026, by match 256): a quiet farmer holds no line.** USE_FARMER_LINE_FREE: holdLine is off while the enemy is a quiet farmer (farmerQuietNow — no hit on us for FARMER_QUIET since the first reach, the same sign that already frees the army's objective from his pack); his first shot brings the line back. Measured apart in the plan worktree on top of v104: the line alone — gate 131/131 at 3 worse / 3 better (scatter 30/31 margins −1 475/−2 780, camp 31 from destroyed to a lead; block 29 +4 356, camp 33 to destroyed, farm+weak 33 +4 631), scatter six of six, camp+shy five of five, spread five of six. The second half — the same exemption for the runner's pack at a flag (USE_FARMER_RUNNER_PACK_FREE) — rejected: gate 129/131 (scatter 19 20 729:24 318 and farm+weak 32 21 155:22 496 lost) although scatter 31 gained 2 841; a runner sent past a pack that the stand's blob does walk after. Kept as a toggle off.

**Matches 259–267 (06.09.2026, the first series of v105): 8:1, the best series on record (1078 -> 1135 -> 1129, rank 1).** けろびー's blob destroyed in 200 — the first win in that form after three annihilations (238, 249, 251) — then MetalicaX in 1 700 (the third win over him ever, 1088 -> 1099), けろびー's farmer in 1 500, あぶらむし forfeit, ricardo in 1 800, けろびー's blob destroyed again in 300 (-> 1116), ricardo in 1 700, Coldkimchi in 1 800 (the first win after 0:13, -> 1135), and then けろびー's blob a third time, annihilated in 200 (-> 1129). The three blob fights by the replay: in the two wins our uptime was 95–100 % by every role (healers next to a wounded mate, melee adjacent, ranged with a target) against his 37–55 % melee adjacency, expected damage 34 140:22 440 and 23 940:16 740 for us; in the loss 10 920:23 580 against us — shots 156:256 (our ranged with a target in three 68 % against his 99 %), swings 6:32, healers 79 %, 54 give-ups. Contact came at t=41 head-on at the centre with his blob formed and ours marching; the fight is decided by who takes the first volleys in formation, and this time it was him. The blob brawl is a coin now, not a certain loss; the opening geometry of the contact is the next thing to read in it.

**v106 (06.09.2026, by match 258): the keeps-distance window survives the push's flicker.** USE_CHASE_WINDOW_WIDE: the paused chase (a HOLD with nobody catchable) keeps the distance window alive while an armed of his is within twice ENGAGE_RANGE of ours, not ENGAGE_RANGE + RANGED_RANGE — in 258 the push followed his touring blob at nine to fourteen cells for a thousand ticks, the posture flickered to HOLD every ten to thirty ticks on the evasive window, and the fifty ticks of v98's stall never accumulated: not one stall in 1 700 ticks. Stand-neutral to the line (gate 131/131 at 0/0 against v105): the stand's blobs do not tour at twelve. The live number is the next tour.

**Matches 268–273 (06.09.2026, the first series of v106): 5:1 (1129 -> 1157 -> 1153, rank 1).** けろびー three times in 1 600, 1 100 and 1 200, G1N6ERbreadMan in 1 000, けろびー forfeit, then Coldkimchi's standing line, annihilated in 800 at his 14 400/16 000. Not a passivity loss: by the replay our uptime was 97–98 % (ranged with a target, healers next to a wounded mate) against his 89–98 %, shots 1 044 against 1 135, healing 80 880 against 86 496 expected — the gap is 91 shots and 34 melee swings (his melee swing in 98 % of their adjacent ticks; adjacency 1–2 % for both sides at his line), a grind of 28 net hits a tick over five hundred ticks. Where it was fought decided it: the unflagged rush at parity sent the army into the ordinary evade at t=49, the points ran (10,71) -> (5,94) -> (3,96) with HOLD/EVADE flickering on the approach rate (79–96), and the line met us at t=98 at the west edge — the army fought at x=3–8 with its back to the wall for the whole match. Corner fights are lost (83, 92, 110, 273) and open-field ones won (66, 75, 79, 93, 96). The old answer, no evade at parity (EVADE_EQUAL_RATIO = RETREAT_RATIO, v87a), was measured again on the v106 stand as v107 and rejected again: gate 130/131 — roost 29 at 99:24 347, an army that is not hunted at parity goes nowhere against a dispersed opponent that never moves — at 23 worse / 13 better, though camp+shy five of five with margins up. The corner as a trap is the open finding of the day's edge items, and it now has the numbers: the evade at parity from a blob at equal speed never escapes, only chooses the wall.

**v108 (06.09.2026, the analysis tools — no rule changed): the decision trace.** TRACE_WHY: a melee with no target while an armed enemy with a fight in it (threatening) stands within ENGAGE_RANGE prints, once a tick for all such melee, which filter removed the throw (`!inLine`, `!covered`, `hold:d3>2`, `giveup`, `!catchable`, `!paired`, `!withPrey`, `!aggr`, `rotating`, `stalled`, `support`) and what it did instead (`holdMelee`, `slot`, `post`, `toCentroid`, `keeper`, …, with `+hold` for the cohesion hold and the step or `stay`), plus a `why-sum` every hundred ticks. Match 251's "our melee stood at two for 117 ticks" was answered by a give-up counter and a guess; now the log names the gate. Stand: 131/131 at 0/0 against v106 — the trace changes nothing — and 18–80 lines per stub match once restricted to threatening enemies (the first cut counted 2 700 creep-ticks of idleness against the unarmed remnants of a won fight in m7 wing). Read by `tools/autopsy.py` as the `melee idle` line and rule.

**v109–v111 (07.09.2026, the ledger's first item — "uptime against the blob" — done by the numbers; two rejected, one to play).** The ledger's `uptime` rule was an artifact: the autopsy counted live parts from the BACK (`hits > i * 100`), so a melee at 700 of 1 600 passed as an eight-ATTACK creep; counted front to back the rule fires nowhere — every creep of ours with a weapon and a target acted (`replay.py silent` on 267: 93 silent ranged creep-ticks, all disarmed, 0 armed; 273: 27 and 0). The corrected rules that fire only in losses over the 290 cached matches: stripped in reach 20 % of losses / 0 % of wins, entry lost 19 / 0, melee adjacency 27 / 0, annihilated ahead 27 / 0, quiet behind 36 / 2, first blood his 26 / 2. Behind them the blob entry (267, t=41–62, `entry-detail`): his damage 4 960 to our 3 000 — nine melee swings (2 160) to our none, 63 shots to 55, 2.3 shots per target-tick to our 1.6 — our ranged without a target in three half the time (50 armed creep-ticks in 3 to his 61: his line stands at three from our melee, our ranged a row behind at four), and our healing on the wrong creeps (the most-hit creep of ours healed in 2 of 19 damage ticks to his 10 of 20; 31 of 57 heal intents on wounded not under fire). **v109** tried the healing: ward and heal target prefer the creep that lost hits last tick (USE_HEAL_UNDER_FIRE, USE_WARD_UNDER_FIRE; the engine lands a heal AFTER the same tick's damage — 667 cases on 273, none wasted — so healing a full creep under fire works). Rejected by the stand's new entry table (26 fight scenarios, hits lost in the first twenty ticks of contact against v108): ward and target worse in 13, better in 5 — the healers walked forward into his fire (m28 nine 1 714 -> 3 010, m34 nine 3 504 -> 4 400); target only worse in 8, better in 0 (sum 75 011 -> 79 738); the share of his focus target healed rose (median 22 % -> 37 %), the exchange did not — and in the record our effective healing already matched his (1 812 to 1 588). **v110**, a melee that does not step back from his melee within three (USE_ALONE_FIRE_NOT_FROM_MELEE — the ghost's `why` trace had three of four melee in aloneInFire at t=44–46): rejected — ghost 267 entry 3 302 lost to v108's 2 846, our swings 8 to 30 (the melee stood in holdMelee at two or three from his and hit nobody); entry table worse 7 / better 3; gate 13/15. **v111**, a given-up press target that comes back within three is a target again (USE_GIVEUP_RETURNS): けろびー feints at the first contact — three ticks back, then in — and our melee gave him up for twenty ticks and stood in slots (`why` from t=49: giveup, slot on all four) while he came back and swung. Gate 131/131 at 4 worse / 5 better; entry table 1 worse / 2 better / 23 same (sum 75 011 -> 74 655, his 245 409 -> 244 716); brawl family: m30 and m34 from a points lead to the blob destroyed, m31 11 -> 12 alive, m35's entry worse (2 580 -> 4 256); the ghost entries of 267 and 264 worse (4 616/3 412 and 1 232/3 744 against 2 846/3 791 and 660/3 114), 273 unchanged — the ghost's blob exchange is not faithful (its README). The live series decides. The stand gained two instruments on the way: an `entry:` line for every scenario (first contact, hits lost by each side over the next 20/50/100 ticks — the fight's quality as a number the outcome does not carry, since the stand's blobs always die) and `his focus target healed` / `a healer adjacent to it` counters in `ours act`; the autopsy its `stripped in reach` and `entry lost` rules and the entry block; the ledger the entry, stripped, march and compactness columns.

**v114 (07.09.2026, the ledger's second item — the tour — with a tourer on the stand at last): the stall lifts the rush veto, and the core is measured over a window.** The stand's new `tour` form is MetalicaX of 258/274/277: the `farm` blob with a twelve-cell step-away radius, firing at what comes within three, walking the flags in a cycle when nothing is left to take. Against v113 it is 7-1 on maps 28–35 (m28 lost 22 558:23 310 — the live shape: stalls fire, detachments come and go, the race is lost by a few hundred). Match 277's two flickers, each answered where it lives. The FLAG/HOLD flicker: `captureAllowed`'s "a fight is imminent" veto followed `approach` across the rush threshold every three ticks while the keeps-distance stall was already saying "he keeps his distance — flags until 521"; the stall — fifty ticks of kept distance, a windowed measure with a three-hundred-tick cooldown — now lifts that veto the way it already lifted the in-contact one (USE_STALL_LIFTS_RUSH_VETO); a real rush shrinks the distance and never stalls. Two other forms were measured first and rejected: the veto only after ten consecutive imminent ticks (v114a, USE_RUSH_VETO_SUSTAINED) opened the flags to real rushes too, whose approach also flickers as the column stretches — scatter m28 24 317:18 115 -> 24 316:24 069, screen m32 −9 515, army m35 from destroyed at 247 to a lead at 1 517; the veto immediate for a new objective and delayed for the current one (v114b) kept army but walked the held objective into real rushes — camp m31 22 868:22 895 red. The detachment flicker: his power against the core over MEASURE_WINDOW (10) ticks — the release measured against the window's maximum, the recall against its minimum (USE_CORE_MEASURE_WINDOW): a hysteresis band the size of the flicker, while a real drop moves the minimum too; the stand's opponents do not flicker the measure, so it changes no stand line (window-only run identical to v113), and the live detach lines are its measure. v114a's recall-after-five-ticks stays off (m30 scatter red). Result: gate 131/131 at 0 worse / 9 better against v113 (farm+weak margins +4 603 / +6 677 / +6 899, camps m29/m31/m33 faster or destroyed, screen+flagless +5 888 / +4 303), the real-rush lines unchanged, tour family 8-0 with m28 at 23 314:20 879.

**Matches 293–294 (07.09.2026, v114's first series: a win over ricardo in 1 800, then けろびー's FARMER in 1 667, 15 846:24 244, 1192 -> 1182) and v115, rejected.** Not the blob: his nine armed split 5+2+2 in the opening — five at D5, pairs to the flags — and took six flags by t=80 to our one; the score diverged at t=80 and the race was lost by then, with our power at 1.53 by t=250 and his blob at twelve to nineteen cells for the rest (six stalls, two of them keeps-distance, 47 detach lines). The detach lines show a third flicker: his largest group of four or five of nine crossed the "at most half" threshold every tick, and `scattered`, with it the opening race and the farmer flag, blinked — the detachment released and recalled on every tick from 76 to 91 (`farmer=true race=true`, then `farmer=false`, then again), 44 lines with adjacent-tick pairs to t=1595. v115 tried two hystereses: `scattered` latched on at "largest at most half" and off only at "largest at least three quarters" (v93's "the race ends by his gathering" made literal), and the detachment's reset on `!farmer` only after five consecutive ticks. Both rejected by the stand. The reset delay alone loses spread m28 and m30 (24 335:21 054 -> 16 862:24 327, 24 326:24 260 -> 10 851:24 326 — against a spread the reset and re-release were the work) and turns m31 camp from destroyed to a lead; the latch alone loses spread m30 (24 320:21 580 -> 16 807:24 318) and storms on the stand's own tourer — m29 tour 240 detach lines and 48 stalls against v114's 15 and 5, because the tourer's blob scatters a step when we come within twelve and gathers again, and the latch clicks with it; gate 131/131 at 2/6. Both stay as toggles off with the numbers. The half-split opening stays open: "largest group at most half" is a label at its boundary, and no tail on it cures either side; the object is the race itself for the free flags while his group holds the centre and his pairs take the edges — a shape the stand does not have yet (`scatter` is fully dispersed, `farm` one blob), and the next form to build. v114 plays on.

**v120 (07.09.2026, the farmer item first — the operator's order after the version-granular map): six deadlocks of the army in front of a flag held by his pair, each measured on the stand and closed, one rejected.** The `split` form on v117 gave the shape: on m28 at t=183 his six sat on H4 in the corner (11–18, 78–85) six cells from our core of ten, his other six in the opposite corner (88–90, 8–11) seventy cells away, and the core of 3 044 evaded the SUM 3 559 — EVADE/HOLD in the corner (19,19) from t=183 to 419 while he held four flags to three. (1) The posture's measure of him is now the pack that will be in the fight: his combat creeps in order of arrival at our mass (path ticks by his body and terrain), the first and everyone arriving before the pack gathered so far dies under our fire (`fightTicks`, healers first, the melee adjacency share as in the power) — a marching column enters whole, a second group in another corner does not; captures, the detachment pool and the core window stay on the whole army. A first cut with a reach radius failed (evasion decides at 25 cells, the pack was empty at 12–25: split m31 24 293:20 333 -> 12 711:24 290); a mass centroid on a wall gave an empty field and the whole army again (`passableNear`). (2) The push measures the pack at HIS head — those arriving at his nearest no later than our slowest striker plus the kill time: with the pack at our mass the army walked into the head of a blob column (entry table v117 -> v120g brawl 4 worse / 1 better, m30 +50 2 536 -> 6 250 own hits, 13 -> 9 alive); at the head, brawl 3 better / 0 worse. (3) The race counted "we are nearer" without the released runners — the runner released for a free flag was the creep we were nearer by, the race ended, he was recalled, released again next tick: 1 260 detach lines on split m31, the posture blinking with him. (4) The rally's vanguard is elected from the mass, as the formation's already is: on split m28 a ranged stuck with three healers beyond his six on D5, 24 cells from the mass, was the vanguard, the mass rallied to him around his group, he leashed back around the same group, nobody moved for 120 ticks and the march stall lifted the push for 300 (13 884:24 289). (5) The mass centroid is the centroid of the largest cluster, not the mean: two stragglers dragged the mean so that four ranged stood four cells from the "centre" at COMPACT_RANGE 2, the compactness rule closed every step toward A3, 1 100 ticks in FLAG at a flag under his three (spread m30 20 654:24 318). (6) A stall lifts the formation: the formation gathers at the creep nearest the enemy, the march follows the flow to the flag, and with a stationary picket beside the path the two pull apart — step, formGo back, gathered, step — a two-tick cycle for 1 000 ticks at D5 under one of his (spread m33 23 645:24 308); the patience from the vanguard's appearance was REJECTED first (split 5-3 -> 2-6, gate 130/131). (7) Healers do not ward keepers: three stood one each by the keepers on D5 and R3 while the strike six at A3 had none. Stand, v117 -> v120: split 3-5 -> 6-2, spread 6-0 -> 6-0 with his score 21 176/21 054/22 734/23 918/20 454/21 516 -> 15 761/13 973/19 435/13 713/15 435/19 785, tour 8-0 -> 8-0, scatter 6-0 -> 6-0 (three narrower), gate 131/131, entry table +20 worse 5 / better 4 with every brawl entry equal or better. The `why` trace names the local numbers behind `!aggr`. OPEN, with numbers: the capturer one cell from A3 refuses the flag cell under a pair's fire at `cost=974 > slack=900` (spread m30 on v120i — the fight's whole cost against one creep's slack, 1 715 against 634 in power); the race trigger follows his kiting step one tick to one (split m34: targets 1 <-> 0, release and clear every tick, the farmer-off clear not setting `detachRecallTick`); split m32/m34 are still lost chasing his groups across the map at 6–11 against 14–19 a tick.

**Matches 303–306 (07.09.2026, v120's first series, from the branch commit): 3:1, 1164 -> 1178 -> 1167.** Two wins on points against ricardo18informatica2020 (1 700 and 1 600 ticks), a forfeit by けろびー in 100, and けろびー#2's blob annihilating us in 340: the opening at the post D5, contact at t=38 at the centre at 4 044 against 3 863, and the entry his — 4 092:2 162 hits in the first twenty ticks, shots 47:71, his melee adjacent 50 creep-ticks to our 12, our melee rotating out (212 idle creep-ticks) as their ATTACK parts went first. None of v120's measures fired before the contact (`fight`/`push` were the whole army all along); this is the blob's entry item as it stood — his ranged front row and our reach — and けろびー#2 is now 2:4 against us. The farmer families the version was built on did not come up in the series.

**The third form of the detachment's blinking (07.09.2026, item 2 of the day's order; match 298 read by its detach lines): named, tried as v121, rejected.** At t=505 two runners are released at 3 885 against 3 234; at t=506 the army's capturer takes A3 and the flag's debuff lands on a core of ten — the posture sees 3 045 against 3 679 (0.83, EVADE), the core "fell under 0.97" and one runner is recalled; at t=512 our own R3 is lost, at t=515 the second runner comes back; three such cycles (505, 952, 1 378), the race lost by 740. The capture gate measures the force after the capture as the army WITH the released runners (`powerAfter`: army + armed runners), the posture measures the core without them: a capture allowed by one measure drops the core under the evade threshold by the other. v121 made the capture gate measure the core and put the army's objective into the after-state (`taking`) — and the stand rejected both: split 6-2 -> 2-6 (m28 24 303:20 588 -> 7 581:24 310), spread m31 24 334:13 713 -> 9 081:24 341, tour m30 22 932:22 068 -> 12 598:22 445 with the core measure; split 6-2 -> 5-3 with the objective alone. The capture measure with the runners is what the farmer race stands on — a runner's capture passing parity while the core sits at the release floor is the race. The inconsistency stays as an open finding with its numbers; the toggles stay off.

**Matches 307–326 (07.09.2026, v120's series of twenty WITHOUT stopping at a defeat — the operator's new rule: the series is the sample, the analysis comes after it, so the work stops switching to whoever beat us last): 15:5, 1167 -> 1185; with the morning's four, v120 is 18-6 and +21 over 24.** Read by the ledger over all of them: every one of the six losses is an annihilation and not one is on points — the farmer and tourer class that cost 280 rating yesterday went 12-0 on points, touring 4-0, sentinel 4-0 (MetalicaX#2/#4 3-0, ricardo18informatica2020#3/#4/#5 5-0). The losses are the blob fight: けろびー#1 1-2 (242 and 243 ticks, −14), #2 2-1, #3 3-1, Coldkimchi#1 0-2 (a blob in 916 and a line in 1 558, −10) — けろびー −36 over 4 losses of 11, Coldkimchi −10 over 2 of 2. The rules that separate the losses from the wins: melee adjacency 6/6 in losses and 0/18 in wins (his melee adjacent 69–173 creep-ticks to our 14–37, 51–173 swings to 12–36), the entry lost 5/6 against 1/18 (3 954:1 869, 3 898:3 766, 6 929:4 406, 3 242:1 110, 1 140:120 — shots 44:70, 68:64, 49:74, 26:59, 9:27), stripped-in-reach 3/6 against 0/18 (our disarmed stood within 3 of his armed half their time, his a tenth), runner stood 3/6 against 1/18 (two scouts POISED 920 and 1 039 ticks beside flags nobody held while Coldkimchi's line dragged the fight across the map for 900 ticks with the score 0:0 — captures are refused in contact, and the contact never ended); give-up storms in all six (27–89 per 100 contact ticks) but in 4/18 wins too, melee idle in all six and in 12/18 wins — hints, not causes. The same blob geometry won and lost: at the centre on t=38–41 at ratio 1.00 we destroyed けろびー#3 in 30 ticks (entry 6 534:9 959, swings 27:21, our melee adjacent 47 creep-ticks to his 29) and were destroyed by けろびー#1 twice (entry 3 954:1 869 and 3 898:3 766, swings 3:1 and 2:2, adjacency 14 to 71 and 14 to 73). The replay of the first says how: his blob steps back diagonally while firing, our melee chase the press target into his fire (melee_1 1 600 -> 752 hits in fourteen ticks), the ranged row trails two behind the melee and out of range of his ranged (44 shots to 70). The stand's ghosts cannot hold this — the recorded opponent does not react, and the build destroys all six ghosts (the entry matches to +20, 2 782:885 against the record's 2 574:1 076, and diverges after) — so the instrument is the entry table's brawl and screen+focus lines. Named for the next version, by weight: (1) the press target is not gated by cover — `engage` demands MELEE_COVER ranged within four of the target since v55, `pressTarget` bypasses it, and the press ring for the ranged was rejected in v96 — so the melee press alone into a stepping line; (2) disarmed creeps stay in his reach; (3) captures refused for the whole of a 900-tick contact. `play.py --history` no longer trips on the System bot's code entry without a version.

**v122 (07.09.2026, the first of the series' findings): the melee press only under cover.** `engage` has demanded MELEE_COVER ranged within four of the target since v55; `pressTarget` did not, and in the replays of the series' losses the melee pressed a stepping-back line alone into its fire (melee_1 1 600 -> 752 in fourteen ticks) while the ranged row trailed out of range. The stand is neutral on it — entry table 25 of 26 rows unchanged (m33 brawl +1 445 at +20 with 13 alive instead of 12), farmer families 21 of 22 identical, gate 131/131 — because no stand form steps back with darting melee; the series of twenty is the measure, and a `dart` form for the stand comes next.

**v123 tried and rejected (07.09.2026, the series' second finding — our disarmed creeps stay in his reach, his leave it).** A stripped creep (no weapon or heal part left) was made to leave the FULL reach of his armed even in contact, where the reach is narrowed to his melee's for the healers' sake. The entry table said no: +20 worse 13 / better 10, +50 worse 13 / better 11 of 26, brawl m31 12 -> 9 alive, m35 13 -> 12, two `fourteen` fights from annihilation to a win on points; brawl+dart 3 worse / 2 better. A disarmed creep beside the front is a sponge for his shots; sent out of three it uncovers the armed and gets no healing there either. What the series' numbers say he does differently is not the fleeing but the healing behind the line — the open item is a healer for the disarmed behind the line, not their flight.

**Matches 327–346 (07.09.2026, v122's series of twenty): 16:4, 1185 -> 1213, rank 1 and the day's high.** By the ledger: the blob went 3-1 (Coldkimchi#1's blob annihilated us in 500 with the entry his again; けろびー#1 and #3 beaten, 欢欢#17 beaten), the farmers and tourers 13-3 — and the three losses on points are one form, けろびー#4 twice (20 583:24 248 in 1 854 with his army down to 5 600 hits, 17 477:24 193 in 1 733 with 5 200 left) and MetalicaX#3 once (23 550:23 977, by 427): the fast tourer. His flag visits say it — R3b and D5 at t=37, A3b and A3a at 55–56, H4b and H4a at 79–80, all seven by t=106 with his scouts, healers and melee all capturing — while our army held one or two flags for the first 300 ticks (rate 3:22 at t=100, 1:6 and 2:5 on the board), and a deficit of 2 000–3 000 by t=300 is not paid back at 8:17 or 12:13 a tick even after the fight is won ten to four. The flag war behind it is a churn (140 and 121 flag events, keepers 12 and 48, detachments 31 and 34): his three scouts retake what our two scouts and the detachments take. The stand's `tour` (MetalicaX's, twelve cells kept, a flag every 150 ticks) is not this form; `greedy` (a blob sweeping the flags one by one) is the nearest, and the next reading is on it. Against the v120 series the draw changed — けろびー#4 did not play then, #1 played three times — so the two series compare by form, not by score: blob 7-5 -> 3-1, points losses 0 -> 3. The press cover (v122) shows in the `why` trace as `!covered` at the top of the melee's idle filters in every tourer match (480, 1 975, 656 creep-ticks): the melee hold where the ranged do not reach — the intended shape, and against a tourer that keeps twelve cells it is idleness the rule did not create.

**The fast tourer on the stand — `blitz` — and three openings tried against it (07.09.2026).** The form (see the stub README) reproduces けろびー#4's opening to the number: flags 1:5–2:4 at t=100, 1:5–1:6 at t=200, four of eight maps lost on points with our army intact (m28 3 961:23 980, m30 4 308:23 483). What the log says: from t=42 to 239 the army pushes his five of 647 power with its 3 575 — they step away within six at the same speed — while his single keepers hold six flags; when the army does take a flag, our keeper is assigned and released within ten ticks (388 -> 397) as his roaming five arrive, and his creep is on the flag by 406 while the army is at the next one. Tried: (v124) the sweep over the chase on the 'does not fight' label — no effect, the label goes out while his keepers flicker in reach; (v124b) the same on the chase's dryness (no fire either way for PASSIVE_TICKS) — m28 turned into a win, m32 into a loss, 4-4 either way; (v125) flags under a keeper a pair beats as race targets — 1-7, with the release/recall storm of v120c again (the pair for the gate counted from the core without the runners) and, corrected or not, pairs eaten one at a time by his roaming five (eight of ours disarmed by t=200). All three stay as toggles off with their numbers. The object is not the release of pairs and not the chase: it is an army that holds what it takes — flags within its own reach, keepers it answers for — against a swarm that never fights and retakes within twenty ticks. That is the next design, not a rule.

**Matches 347–366 (07.09.2026, v122's second series of twenty): 14:6, 1213 -> 1228 with a high of 1257; over v122's forty, 30-10 and +43.** The ledger over both series: on points 19-3 (touring 15-4, sentinel 4-0, scatter 2-0, farmer 2-0 — the farmer class is closed on the live sample), the blob 7-6 — and this series' six losses are five annihilations in blob fights and one tourer race: MetalicaX#8's blob in 260 (entry 6 756:5 788, swings 22:34), Coldkimchi#1 twice (506: entry 2 983:1 946, his melee adjacent 52 creep-ticks to our 16; 602: entry 3 612:1 350, shots 29:61, our disarmed in his reach 182 of 225 creep-ticks to his 9 of 15), けろびー#3 at the wall (400: 100 % of the contact within 8 of the edge, entry 7 942:4 598, first volleys 18:8, adjacency 87:18), MetalicaX#2 after a fight won on the entry (1 490: 4 272:7 125 our way, then 690 quiet ticks behind while he toured and ground the remnant down), and けろびー#4's race again (1 940: 22 914:24 176, our 16 000 hits intact against his 9 088). The rules that separate v122's ten losses from its thirty wins over the forty: stripped-in-reach 4/10 against 0/30, melee adjacency 4/10 against 1/30, entry lost 4/10 against 3/30, first blood his 6/10 against 7/30. Rating leaks: けろびー#4 −31 over six (3-3), Coldkimchi#1 −17 (1-3), MetalicaX#2 and #8 −24. Two items of one weight: the blob's entry at parity (our melee swing 11–20 to his 50–87 — rotating out at half their ATTACK parts and the throw's line gate at the top of the idle filters) and the fast tourer's opening, which this day's three tries did not turn.

**v126 — the rotation's return one part above its exit (07.09.2026, after the five blob annihilations of 347–366).** The why trace of the lost blob fights puts `rotating` first among the idle melee's filters (506: 417 of 602 idle creep-ticks, 602: 589 of 871; the wins 160–170), and the rotation spans read off the trace are 50–97 ticks in the losses (506: melee_4 out 116–212 and 244–303, 602: melee_3 314–372, melee_2 59–108) against 5–22 in the wins. The replay says why: the rotating melee stands two cells from his melee at 1 050–1 250 hits for a hundred ticks — the nearest healer is at the front, fire and healing balance there, and the return at 0.9 of an eight-part ATTACK block is all eight parts, a full heal that never comes under fire (his melee in match 14 came back at five of eight). The stand agrees on the mechanism (brawl rotations 14–52 ticks; his volley takes six of eight parts in a tick: `out frac=0.25 hits=994`) and measures the rules: no rotation at all — 18 worse / 6 better at +50, dart m29 not destroyed, m31 13 -> 10 alive; thresholds 0.25/0.6 — 16 / 8; the return at five of eight — 10 / 11, at six of eight 11 / 10; the rotating fighter sent to the healer over its rear slot — identical to the digit on all 34 runs. Adopted: `USE_ROTATE_IN_ONE_PART` — the return when one weapon part more than the exit line is alive, computed from the body (five of eight for an A8, four of six for an R6); the rotation is measured in the log (`rot t=… out|in … took=`). The second finding of the day is the exchange itself: in 506's decisive window (t=74–130) both sides' ranged fired on every tick they had a target, and his had one 162 of 280 creep-ticks (57 %) against our 113 of 268 (42 %) — 158 shots to 104, 63 of his with only our melee in reach against 32 of ours. The geometry: his melee poke stands at 2–3 from our front without striking (adjacent 3–5 % of creep-ticks), our rows put the ranged 3 − d behind the front melee — 3 from the poke and 4–5 from his ranged behind it — while his ranged stand 3 from our front melee. The same split opens every Coldkimchi fight of the series (41–42 % against 49–57 %) and MetalicaX's 260 (52 against 58); in the wins it is even or ours. The line against the poke (`USE_LINE_VS_POKE`: ranged and melee in one row at RANGED_RANGE from the poke while none of his melee is adjacent) is written and OFF: the stand has no poke — brawl strikes, dart leaves after the swing, screen keeps three with ranged — so 24 of 26 scenarios did not change and the two that did are worse (m30 brawl +50 2 536:10 038 -> 3 648:10 567, m31 12 -> 10 alive with our reach 51 -> 31 %), and the ghosts of 506/602 do not converge (stand contact at t=393 and 518). The stand's own reach share, now printed for both sides (`… (24 % in reach, his 63 %)`), is a chase artefact on brawl (the fight itself is reach 5/5) — the instrument for the poke is a `poke` form, to build before the line is measured. The `!inLine` gate without rotation is 126/196 idle creep-ticks in the losses against 275/112 in the wins — not the separator. Match 400 at the wall is another class: reach 82 % against 72 %, but 65 disarmed ranged creep-ticks in reach and his melee 75 swings to our 18.

**Matches 367–386 (07.09.2026, v126's twenty): 13:7, 1228 -> 1226; by his version — blobs 0-3 (けろびー#1, Coldkimchi#1, けろびー#3 at the wall), MetalicaX#3 0-2 (the fast tour: D5 at t=41, five flags by t=84, 580 quiet ticks behind), MetalicaX#2 1-1, MetalicaX#4 2-1, けろびー#12 3-0, けろびー#4 1-0, ricardo18informatica2020#4/#5 3-0, arukuka#5 and 欢欢#15 1-0.** The rotation's return one part above the exit did what it was built for and nothing more: rotations in the lost fights now take 6–25 ticks (match 3), median 12 with one 60 (11), 30–35 (7) against 50–97 in 347–366 — and the fights are lost the same way, so the long rotation was the symptom, not the cause. The cause the new `reach` rule of the autopsy names in all three blob losses and one win of twelve: in the first sixty ticks of contact his ranged had a target within 3 in 67 % of their creep-ticks against our 41 % (けろびー#1; the entry 4 112:1 820 hits, shots 40:65), 60 % against 36 % (Coldkimchi#1; 3 796:1 256, 35:58, 108 stripped creep-ticks in his reach) and 83 % against 72 % (けろびー#3, a corner fight with his melee adjacent 101 creep-ticks to our 33). His melee poke at 2–3 keeps our front melee in reach of his ranged and our ranged a cell short of his — the line against the poke (v126, OFF) is the answer to measure, and the stand has no form for it yet. The other two losses are fights the doctrine says not to fight: in match 7 the army at +3 066 (+22/t against +3/t, six flags of seven) took the seventh flag at t=1302 — every debuff ours (A×0.6 R×0.6 H×0.5 D×1.1), none his — the capture gate passed at 2 247:1 925 (CAPTURE_FLOOR 1.0), the press sent the pack in at 1319, and eleven creeps were three by 1335 (his 50 mass attacks in 35 ticks); in match 15 at +1 658 (+15/t against +10/t) the pack went in twice (t=628, 654) at a measure of 3 385:2 938, and 14 484 hits were 4 763 thirty ticks later (his melee 39 swings to our 10, 53 mass attacks). Both gates read the power measure, and the measure overstated us by more than two times in both; a lead with the rate to keep it needs no fight at all, and the seventh flag never pays. Tools this batch: match records come from the API through the client into `~/ScreepsArena/games/` (the operator closed the client's cache as a source; `tools/match-log.py fetch`, `play.py` writes as it plays), the ledger and `series.py compare` read the opponent as name#version, the autopsy prints the opening reach per side and fires `reach`. Next: the fight-while-ahead gate and the seventh flag; the poke form on the stand, then the line; MetalicaX#3's and けろびー#4's opening.

**v127 — a lead with the rate to keep it needs no fight, and the seventh flag is never taken (07.09.2026, after 367–386).** Two rules out of matches 7 and 15, both computed from the state: `USE_LEAD_HOLDS` — ahead on the score and on its projection to the end, with his army alive, the push (`pushing`) and the press pack (`pressing`) are off, and a fight happens only by the contact he brings; `USE_NO_SEVENTH_FLAG` — a capture after which all seven flags are ours is refused while his army lives (it puts every debuff on us and none on him — A×0.6 R×0.6 H×0.5 D×1.1 against ×1 at t=1302 of match 7 — for +3/t). The stand: the entry table and dart unchanged to the digit but m30 nine (his last creep now caught), the families the same on 20 of 22 lines, camp m33 22 529:9 705 -> 22 521:15 263 and m35 23 326:14 489 -> 23 322:12 243 (the lead held instead of the remnant hunted, both wins), scatter identical. Tried on top and rejected: `USE_RUSH_NOT_FLAG_BOUND` — a massed blob walking to a free flag nearer to it than we are read as touring, not rushing (match 5, MetalicaX#3: rush=true from t=10 to 41 while he went to D5, our army at the post, captures vetoed, his D5 at 41 and five flags by 84, our first at 54) — blitz 4-4 -> 2-6 (m31 23 931:21 418 -> 5 412:23 900, m35 23 880:21 378 -> 3 641:24 004), the entry table 4 worse / 3 better with m8 hunter +20 3 919 -> 6 002 and m30 screen+focus 3 324 -> 4 157, split m33 from a win to 19 224:24 286: the centre lies on the path of every rush, and a blob walking to it cannot be told from one walking through it until it has passed the flag. The opening against the fast tourer stays open with its mechanism named: the rush hold and the parity floor (a first flag at exact parity fails 0.97 for everyone, runners included) give him the first forty ticks. The stand gains `screen+focus+poke` (the README has the numbers): the line's fighters rotate like Coldkimchi's, the line lasts longer (m35 219 -> 414, three maps end as his lead-proof remnants) and our reach stays below his (13–37 % to 28–60 %), but its entry is still ours three to one where the live line's is his — the instrument for the line against the poke is closer, not there.

**Matches 387–406 (07.09.2026, v127's twenty): 16:4, 1226 -> 1252; by his version — MetalicaX#3 2-1 (0-2 under v126), MetalicaX#4 2-1, MetalicaX#2 1-1, けろびー#12 3-1, けろびー#3 1-0 (the wall blob of 400 and 18, annihilated by t=100), ricardo18informatica2020#4 2-0, raznikk#11/#12/#13, Xeon_Lagunas#6 and the System bot 1-0 each.** The blob class that lost three of three under v126 lost none: けろびー#3 in a hundred ticks, and MetalicaX#2's brawl in match 4 won on the entry 7 813:11 571 (shots 58:27); `reach` fired in no loss and two wins. The two rules of v127 did what they were built for — the lead held on in match 19 at 7 768:7 765 with the rate 15:10 and let go at 733 when his flag turned the projection (rate 10:15), and no seventh flag was taken — and none of the four losses is a fight the lead did not need. All four are the tourer: match 8 (けろびー#12) and 19 (MetalicaX#3) open exactly as match 5 did — `rush=true` from t=10 to 39 with the army at the post, his D5 and R3 at 39–44, six flags by 80–91, our first flag at 42 and 98 — and 8 is lost on points with both armies intact, 19 by the fight that came when the projection turned (12 -> 7 creeps from parity, his melee adjacent 64 creep-ticks to our 28, 76 stripped creep-ticks left in his reach) and a remnant of six that stood 500 ticks without a flag; match 13 (MetalicaX#4) is lost by 691 with both armies whole, his D5 (5/t) against our four cheaper flags; match 4 (MetalicaX#2) won the opening brawl and lost the tour with the remnant — 5 creeps at 919 against his 4 at 1 159 by the measure, evading 900 ticks while behind. The stand's `blitz` reproduces that opening to the tick (rush hold 10–30, his R3/D5 at 39–41, ours at 41–42, his A3 at 57–63), so the opening has its instrument; the flag-bound reading of the rush lost on it (4-4 -> 2-6) and the mechanism stays named: the rush hold and the parity floor at exact parity give the tourer the first forty ticks and the centre flag, and every fast tourer takes D5 first. Over the day's sixty (v122's second twenty, v126, v127): 43-17, 1213 -> 1252; the ledger now reads the whole arena history from the API store (143 matches, 35 opponent bots), where けろびー#3 stands 4-4 (−31) and けろびー#4 5-5 (−28) across our versions.

**The opening against the fast tourer, two probes on `blitz` (07.09.2026, after 387–406) — both off.** The mechanism named by matches 5, 8 and 19: `rush=true` from t=10 to 39 holds the army at the post and vetoes every capture, the parity floor refuses the first flag to everyone at exact parity, and the tourer takes D5 (5/t) at 39–41 and six flags by 80–91. `USE_RUNNER_HALF_UNDER_RUSH` — a runner may take a flag on our half during the hold, the floor still applying — leaves blitz to the digit (the floor held the runners anyway) and improves tour on five maps, but the entry table is 17 worse / 5 better at +20: brawl m33 turns from his annihilation into ours (+50 2 628:11 684 -> 8 051:6 038), m32 brawl 11 -> 7 alive, m30 brawl +50 3 028:12 818 -> 5 940:5 767 — the runner's debuff before the real fight, the lesson of v47, costs more than the tour pays. `USE_HIS_FLAGS_IN_FLIGHT` — a free flag with his armed creep adjacent counts as his in the power after a capture — is neutral: blitz and the entry table identical, tour m30 better and m34/m35 worse; the adjacent window turns a gate decision too rarely. Both stay off with their numbers. What the stand prices, then: the forty ticks the tourer takes are the price of not walking into a rush debuffed, and the three ways of not paying it tried today (the flag-bound rush, the runners under the hold, his flags in flight) all lose the fights the hold protects. The stand's `+poke` got its second cut (the line's ranged level with the melee, as the live line's are in 506's entry) and the entry against it is still ours three to one (+20 2 140–4 034 to his 9 771–12 688) — the gap is what the live line does with its healers and its fire (focus target healed 52 % live against 36 % here, four guns on one creep), the next thing to copy before the line rule can be measured.

**The line on the stand, third cut (07.09.2026): the mirror measure, and where the gap is.** The stand's `ours act` line now prints the mirror of its healing measure — OUR focus target healed by him, his healer adjacent to it — and under `+poke` the line's healers walk to the creep that lost hits this tick and heal it first (a first version of the mirror ran before his intents were placed and read zero; it runs after them now). The trace says the mechanism works — a healer stands at one or two from the creep under our fire within three ticks — and the number does not move: his focus target healed 2–14 % of its damage ticks on eight maps against 52 % live, because our fire on that creep is 360–560 a tick: the poke melee that steps in to one dies in two ticks under our four melee (our melee adjacent 40–60 creep-ticks per fight on the stand, 16 in the whole of 506), and no healing outruns that. So the gap between the stand's line and Coldkimchi's is not the healers and not the ranged's distance (second cut): it is how his poke reaches our ranged and healers without meeting our melee. That is the form to build next; until then `USE_LINE_VS_POKE` stays off.

**The opening's fourth probe and the stand's fourth cut of the line (07.09.2026, evening).** `USE_POST_TOWARD_CENTRE` — under the unflagged rush the army posts at the our-half flag nearest to the centre (A3, 25 from D5) instead of the nearest to itself (R3 in the corner), so the second flag comes thirty ticks earlier — is off with its numbers: blitz 4-4 -> 3-5 (m31 23 931:21 418 -> 8 049:23 597, m32 23 975:21 586 -> 4 379:24 104), the entry table 9 worse / 4 better at +50 (brawl m28 486:13 518 -> 5 126:10 606, m35 2 632:12 727 -> 5 868:4 741), split m28 and m35 from wins to losses, tour better on three maps: an army that waits nearer the centre meets the rush and the blitz groups earlier and worse. Four probes of the opening against the tourer in one evening — the flag-bound rush, the runners under the hold, his flags in flight, the post toward the centre — and the stand prices every one of them in fights; the rush hold at home is the price it has set, and the next idea has to tell a tour from a rush by something other than tempo, the flag on the path or where the army waits. On the stand's line: `+wall` — his melee as the replays of 506/602 show them, a screen at three level with the ranged (279/352 creep-ticks at three from our melee, 322/311 at four, 26/21 adjacent, 16 and 4 swings a fight) — and the entry is still ours three to one, because our press catches the stand's line when it steps back (its own block blocks its retreat) where the live line steps back clean and our melee give up after twenty ticks. Three cuts and one wall: the line the rule needs has a retreat that works, and that is the next form.

**Matches 407–426 (07.09.2026, late evening, v127's second twenty on the same build, no landing between): 16:4, 1252 -> 1317; v127 over forty matches 32-8 and +91, the best build so far by the whole sample.** The four losses: 407 (MetalicaX#4, tour — 570 ticks behind with no target in reach, annihilated at the limit with his lead reachable), 411 (Coldkimchi#1, blob — the entry his 3 446:1 948 with shots 40:59, his reach 51 % against our 32 % in the first sixty ticks, a corner fight 61 % of 413 contact ticks, 273 press give-ups, annihilated at t=670), 419 (けろびー#3, tour — 510 ticks behind in EVADE, annihilated at 720), 421 (MetalicaX#2, stood — 560 ticks behind in HOLD, annihilated at the limit with his lead 7 416 reachable). Three of four are the open class — the tourer we cannot catch up with once behind — and the fourth is the fight the stand has been trying to model all evening, now with a third replay of it. By opponent bot over the forty: MetalicaX#2 3-2, #3 3-1, #4 4-2, けろびー#3 3-1, #12 4-1, Coldkimchi#1 2-1.

**The stand's line, the fifth form (07.09.2026, the same evening): what the live line IS, measured, and the form that has its adjacency.** Two instruments on replays 506/602 and 411 (Coldkimchi#1), by the axis from his armed centroid to ours: his block at contact is 5.5 wide and 4 deep for twelve creeps, the roles MIXED (melee, ranged and healers all at depth 0), a healer adjacent to a melee 80 % and to a ranged 64–75 % of creep-ticks, his centroid still 45–48 of 56 ticks and six steps back in a fight; and the dance — when one of our melee stands at two of his creep, that creep steps AWAY 67–79 % of the time, the next tick the distance is 2–4 (one in 7 %), our melee swung 0 and 3 times in two fights while his melee stood at two of ours 246–303 melee-ticks. That measure rejected two forms before they were finished: `+retreat` (the block one cell back when one of ours is at two — three cuts) models a block that runs, and the live one does not (entry +20 0/1188 in the first cut, a chase across the map in all three); `+square` (R M M R / R H H R / M H R M, then a loose 5x4, then the front swapping with the creep behind — six cuts) models a block that stands, and a creep in a formed block has no free cell to step into: our melee adjacent 9–18 % of their creep-ticks against the live 2 %, 56 adjacent creep-ticks x 240 the block's whole loss, the swap putting a healer on the cell our melee was already at two of. `+blob` has no rows: every creep keeps three from our nearest armed creep by itself, stands and shoots at three, walks toward the focus beyond three but never farther than two from the centroid or without a healer within two; healers go to the creep that lost hits this tick. It is the first form with the live adjacency (our melee adjacent 2–7 %), his reach 40–54 % against our 6–45 %, and it still loses the entry (+20 1 679–2 932/3 628–6 084, +50 by four to eight times): the gap left is named by its healer trace — his healers heal the creep under our fire 6–14 % of its damage ticks against the live 52 %, because on the stand our fire changes target EVERY tick (the creep that lost the most hits: 31 -> 27 -> 30 -> 24 -> 31) and three healers run after it three to five cells behind; live our fire holds one target long enough to be healed on. The next instrument is our own fire concentration on the stand against the replay's. `USE_LINE_VS_POKE` measured on `+blob` (eight maps, on against off on the same build): three maps unchanged (the rule does not fire), m31 better (destroyed at 562 instead of the limit), m29 worse (destroyed at 223 -> alive 14/5 at the limit), m34 worse (+20 3 507/3 954 -> 2 666/1 772), m30 mixed — off it stays, now measured on a form with the live adjacency rather than on none.

**v128 (07.09.2026, night): the healers cover the block.** The finding came out of the persistence measure on replays 506/411 (`replay-persist.py`): both sides' fire lands on a different creep almost every tick (the most-shot creep is the same as the previous tick's in 31–44 % of ticks), yet his healer stands adjacent to the creep our fire lands on 62–83 % of ticks and ours to the creep his fire lands on 14–16 % — his healers do not chase the wounded, they sit inside the blob touching as many fighters as they can, and whichever creep the fire lands on has one next to it. In `planFight` the healer's cell key gets one term after "not within two of his melee": the number of our fighters adjacent to the cell that no placed healer touches yet (greedy set cover), before the largest incoming damage. Priced on the stand against v127: blitz 4-4 -> 6-2 (m28 3 961:23 980 -> 24 033:22 229, m33 16 255:24 133 -> 24 137:23 351), the tour family better on five maps of eight (m34 his army 14 827 -> 2 558 with four of his alive), split m32 from a loss to a win, and the entry table +20 worse 4 / better 3, +50 worse 7 / better 6 with no flip to a defeat (brawl m31 7 252/5 519 -> 2 546/11 547, m33 2 628/11 684 -> 3 428/9 457; the sum of the +50 differences 8 213 in our favour). The plain neighbour count (v128a) was rejected on the way: it crowded the three healers onto one dense group and left the rest — +50 worse 7 / better 4 and brawl m31 from his army destroyed to ours.

**The reach gate on `hunted` (07.09.2026, night, priced and rejected), the stand's `OURWEAK` scenario, and the first-tick timeout.** The two quiet losses of 407–426 had one mechanism: `hunted` is his fight power at 1.15 of ours and nothing else, so in 407 the bot chose FLAG (his H4 at (8,90), travel 43) at t=1268 and EVADE at 1269 with his army 47 cells away and not closing, and stood at R3 for 530 ticks behind by a reachable 1 750–3 250 with two runners idle. The probe — not hunted while his nearest armed creep is beyond EVADE_RANGE + APPROACH_WINDOW, his approach rate below APPROACH_RUSH and the race already lost — fired in no scenario of the stand's grid (identical to v128 on the 26 entry scenarios, eight blitz maps and 22 family runs), because the stand's tour never costs us a creep; `OURWEAK=N` starts our army without its first N melee (407's shape: power 0.855) and priced it in three cuts: tour 5-3 -> 6-2 every time, split 7-1 -> 3-5 by the armed centroid, 4-4 with the lost-race condition, 4-4 with the nearest creep instead of the centroid — without `hunted` the army goes flagging into the groups of a split farmer it was beating on points, and on tour m28 the toggling of `hunted` at the boundary resets the evasion state and finds an exit the steady state did not (9 246:17 774). Off with its numbers; 407's subject stays open. Two more instruments were written on the way (in the job's folder, not the repo): `replay-persist.py` (the most-shot creep per tick, its persistence, the healer adjacent to it) and `replay-brawl.py` (whom his melee stand next to, where ours are then) — the second says of 419 (けろびー#3) that his melee ate our ranged and healers (87 of 122 adjacent pairs) while our melee were five or more cells away in 74 of 122, and of 421 that our melee stood adjacent to his in 91 of 148 pairs and swung 40 times to his 93. On the stand's line, `+blob`'s second and third cuts (healers inside the blob on the cell touching the most fighters, off a pressed fighter's step-back cell) bring the entry to even or his on three maps of six (m29 2 652/2 668, m34 1 338/1 478, m35 2 206/1 586) with our melee adjacent 1–12 %, and his healing of the creep under our fire 12–20 % against the live 52 % is still the named gap. And a defect older than any of this, found by `autopsy`'s `[errors]` line while reading the v128 losses: `Error: Script execution timed out` at `getStringHashCode` on tick 1 in 12 of 20 logs of each v127 series and 9 of 11 of v128 — the first tick is killed by the limit in most matches, won and lost alike; open.

**Matches 427–446 (07.09.2026, night, v128's twenty): 13:7, 1317 -> 1287 — the stand's gain did not come through, v128 is off and v127's behaviour plays again.** v127's two series went 16:4 and 16:4; v128 went 13:7 and lost thirty rating. By opponent bot, v127 against v128 on the bots both met: Coldkimchi#1 2-1 -> 1-3, MetalicaX#2 3-2 -> 0-1, MetalicaX#3 3-1 -> 1-1, けろびー#3 3-1 -> 0-1, けろびー#4 1-0 -> 0-1 — the strong shared bots 12-5 -> 2-7 — and the wins came from ricardo18#4 (4-0), けろびー#1 (2-0) and MetalicaX#8 (2-0, a bot v127 never met). The seven losses are the day's two classes and nothing new: three Coldkimchi#1 blob fights (the entry his 4 386:968 and 4 410:1 776 with shots 26:67 and 31:61, `reach` 53 % against 38 %; one annihilated while ahead 2 877:70, the parity doctrine's own case), two tourers on points (MetalicaX#3 550 ticks behind with no target in reach, 1 826 melee creep-ticks idle of which `!covered` 1 592; けろびー#4 with his lead 12 296 reachable), and two more of けろびー/MetalicaX by the same shapes. What v128 did do live, measured on the first Coldkimchi loss by the block instrument: our healer adjacent to a melee 34 -> 40 % and to a ranged 52 -> 62 % of creep-ticks (his 71 and 54), our healers still a row behind (depth −0.9 on the axis against his +0.2) — the rule moved the healers a little and the entry not at all. The whole sample says no: `USE_HEALERS_COVER` is off with these numbers, the greeting says v127 again, and the stand's entry table, which had this at +50 worse 7 / better 6, was the honest reading — a mixed table is not a version. Day total 07.09: 100 matches 72:28, 1213 -> 1287.

**v131 (07.09.2026, late night): the tick inside the limit on a cold VM — and the runner's free flag priced and rejected on the way.** The `[errors]` line of autopsy had said "Script execution timed out" on tick 1 in 12 of 20 logs of every series, and the bot had no measure of its own CPU. Now it has one: `cpu t=N total=…ms: built= prep= arrival= r.cands= runners= a.hunt= a.retreat= a.sweep= a.escape= a.obj= a.evade= posture= plan= moves= shoot= resolve=` for the first three ticks, every hundredth and every tick over 60 ms, and `cpu t=N: max=… at t=… slow(>60ms)=… limit=100/1000ms` every hundred ticks; the stand's `getCpuTime()` counts from the tick's `loop()` call, so the same lines come out there. What it measured live: the limit is 100 ms a tick and 1 000 on the first; tick 1 costs 176–187 ms (fields 55, the runners' candidates 61, the posture 44), ticks 2–35 cost 60–95 ms and tick 2 timed out in every measured match (once tick 277 too, in a fight); after t=100 the same code costs 20–55 ms, and on the stand 6–9 ms — the cold JIT is an eightfold factor over every phase, and a timed-out tick loses all fourteen creeps' intents. The two heavy phases are the runners' candidate loop (seven flags x flow, travel, pack and capture cost per runner, every tick) and the posture's flag objective (seven flags x capture cost, flow, twelve path walks and the pack) plus the escape flows. Three cuts: (a) flow fields to flag cells kept thirty ticks instead of five — rejected: it changes the BFS budget's sequence (fewer flag fields a tick, other fields computed in full instead of bounded) and the stand priced that as mixed, entry table 3 worse / 0 better, blitz 4-4 -> 2-6, split better on three maps; (b) the flows to all seven flags prefetched on tick 1, whose budget is 1 000 ms, so tick 2 finds them cached; (c) a CPU guard — a tick already past 50 ms (never on tick 1) lets the runners keep their flags without re-evaluating the candidates, and the posture evaluate only the current objective flag and keep its escape flows. (b)+(c) are v131: the stand is identical to v127 on all 26 entry scenarios, eight blitz maps, 22 family runs and sixteen one-melee-short runs (warm ticks never reach the guard), and live two matches had no timeout at all — on tick 2 the guard fired at 76 ms and the tick ended inside the limit, 5–21 ticks over 60 ms in the first hundred, none after. Before it, on the same evening: `USE_RUNNER_FREE_FLAG` — an unarmed runner takes a flag past the exit-margin veto when his nearest threatening creep is farther from it than the runner's travel plus its flee trigger (407's idle runner) — priced on the one-melee-short scenarios and rejected: tour 6-2 -> 6-2 with maps flipping both ways, split 4-4 -> 2-6 (the runner walks between a split farmer's groups and hands the flag over with itself); 407's subject stays open after two probes. And the stand's `+blob` got its fourth cut (healers cover the front: his healer adjacent to the creep under our fire 12–30 % against the third cut's 6–16 % and the live 62–83 %).

**v132 (07.09.2026, night, from the Coldkimchi loss the operator watched): the stall detector measures his nearest group, and the pack at a flag is walked once a tick.** Match 6a9f0af1 (series 447–466, the fourth): at t=250–320 five of his — four ranged and a melee, no healer — sat in the corner at (1–4,1–6) two steps from our eleven whole creeps, his three healers and three melee 25–40 cells away; the operator saw us leave them. The log says why: `stall t=274: the enemy keeps its distance (8 -> 12 over 8 ticks) without damage either way — flags until 574` — the detector measured our distance to the centroid of his WHOLE army, which drifted with the far six while the cornered five stood still; the army went flagging, the race/detach rule then read his split army as a farmer and detached five of ours to flags (`detach t=293: 4 detached … race=true largest=5/11`), the core measured itself weaker (`our=448 enemy=1138`) and RETREATed into his corner, and by t=330 he had regrouped (power 1138 -> 3446); the fight ran to t=1220 and we were annihilated ahead 3 736:0. The same match had eight timeouts and 95 ticks over 60 ms in t=300–400 — the phase lines name `r.cands` 52–73 ms: five detached runners x seven flags, and `packAt` walking the flow field for each of his eleven creeps per pair, ~150 000 steps a tick; the guard stands before that loop, when the tick is still cheap. v132: (1) `USE_STALL_NEAREST_GROUP` — the keeps-its-distance detector measures the distance to the centroid of his group nearest to us (his armed creeps within ENGAGE_RANGE of the one nearest our armed centroid) and records that group's centroid; (2) `USE_PACK_MEMO` — the enemies' path ticks to a flag cell are computed once a tick per flag and field and reused by every runner and the army, the same result at a fifth of the work. The stand against v131: the entry table identical on all 26, blitz 4-4 -> 4-4 (m28 to a win, m32 to a loss), the split family +2 (m31, m32 to wins), tour with one melee short two maps with his army cut to four (m32, m34) and two flips each way, split with one melee short 4-4 -> 5-3 — and the landing gate said no: in the regress the stall-by-group turned spread m19/m24/m29, farm m31 and scatter m31 from points wins into points losses (21 782:24 309, 21 763:24 272, 18 582:24 318, 17 356:23 672, 24 035:24 318) — against a DISPERSED enemy the nearest group is one or two creeps that never keep their distance the way a centroid does, the stall never comes, and the army chases instead of flagging. `USE_STALL_NEAREST_GROUP` is off with those numbers, `USE_PACK_MEMO` stays (pure), and v132 is the memo alone; the corner's subject is open with its next form named — a stall that tells a cornered group from a dispersal by the group's own stillness and weakness in reach, not by a distance.

**Matches 447–466 (07.09.2026, night, v131's twenty): 16:4, 1260 -> 1315 — and the first series with the bot's own cpu line in every log.** Over the twenty: two matches with a timeout (nine timeouts, eight of them in the cornered-Coldkimchi match at t=294–408, the fight ticks 80–99 ms) against twelve of twenty in every series before; the first hundred ticks 4–22 ticks over 60 ms, the maxima after t=100 mostly 20–66 ms. By opponent bot: MetalicaX#4 3-0, #8 3-0, #3 2-1, #2 1-0, けろびー#4 4-0, ricardo18#4 2-0, Coldkimchi#1 1-2, けろびー#12 0-2, arukuka#5 0-1. The four losses: the cornered Coldkimchi (above — the stall detector and the race, v132's subject), a second Coldkimchi#1 blob annihilated ahead 1 040:515 at t=360 (the entry class), けろびー#12 on points (520 ticks behind with no target in reach while he toured — the open tourer class), and arukuka#5 in a 120-tick blob fight (rotation 63, `!inLine` 52 of the melee's idle creep-ticks). v127 over its 53 matches stands at 42-11, v131 at 20-6 over 26; the strong shared bots hold or improve except Coldkimchi#1 and けろびー#12, the two blob-and-tour bots that decide every series.


**Matches 467–486 — v132's twenty (07.09.2026, night): 15:5, 1 315 → 1 306, and what the sample says.** By his bot: けろびー#12 3-3 (with v131's two: 3-5), MetalicaX#2 0-2, ricardo18informatica2020#4 6-0, MetalicaX#3/#4/#6 3-0, けろびー#4, raznikk#13 and System 1-0 each. CPU is closed: over the twenty logs the slowest tick was 52 ms, no tick over 60, no `timed out` (v131: 2 matches of 20, before it 12 of 20) — the pack memo did it and the third guard is not needed. All five losses ended on points before the limit with both armies whole: the arena ends a match the tick the lead exceeds 25 per remaining tick (every flag's rate together), measured exactly on the wins (1859, 1931), and the autopsy had read all five as "annihilated between samples" because the console log stops at its last full hundred — fixed (`outcome_form`: the end is the replay's tick count, both alive at it is a points end). Three classes. **A, けろびー#12 (three losses):** the opening costs ~2 000 points by t=200 in six matches of six — his singles touch all seven flags by t=80 (D5 and R3 at 37, the A3s at 52/55, the H4s at 75/78) while our army sits in ANNIHILATE split 6/4 chasing them (rate 3/22 at t=100), and the race rule had one runner out for 17 ticks; the three wins are the matches where the mid-game settled into HOLD at 15/10, the losses the churn; the decisive moment of 1982db is t=1700: his eleven stand as a block on D5 under the flags' debuffs (A×0.6, H×0.5), our twelve at eight cells with 4 075 against 2 855 and pushing — and the army moved four cells in 28 ticks, then stalled and walked to R3 36 cells away at 7 against 18. The per-creep trace names it: `hold=true(form)` on every melee and two ranged, the other ranged walking "to the van" and stepping sideways under the density penalty — the formation gathers FORM_SHARE of the armed within two of the van, the van is by definition the FRONT creep, and the 5×5 around it holds only the front half of a three-deep blob; FORM_PATIENCE gives one step per ten ticks. **B, MetalicaX#2 (1982f4):** ahead 20 652:19 903 at rate 22/3 at t=1700; from 1580 the race (a quiet chain, the pool by power — the ranged first) had four of five ranged out on flags, the core of four melee, three healers and one ranged stood at A3, his compact eight (four ranged, a melee, three healers, largest group 5 of 5, dispersion 0 %) walked in and kited it: 218 shots to 103 over ninety ticks, seven of ours by 1780, lost 22 207:20 848 at 1983. The core's power measure held because melee are priced high, and a melee never catches a ranged of equal speed. **C, MetalicaX#2 (19830e):** twelve on twelve at D5, his block from eighteen cells to contact in fourteen ticks into our HOLD; the exchange 9 800:6 200 his way, six of ours dead by 1200 against none of his — 74 swings to our 38 at adjacency 76 to 62, our melee disarmed on 24 of their adjacent ticks against his 2: his melee hit our melee (17 of 30 with our armed melee adjacent) and stripped their ATTACK, ours hit his ranged (16 of 27 with his armed melee adjacent) by the focus order. The ledger's `annihilated ahead` rule, asked for the "remnant at a losing projection" item, has four losses and all are Coldkimchi#1 — that item is the blob fight, not a subject of its own.

**v133 — the formation gathers as a clump at a still block, the race keeps the ranged, a cornered group cancels the distance stall.** Three cuts from the three classes and the corner match, priced separately. `USE_FORM_CLUMP`: a former counts as gathered within FORM_RANGE of the van OR adjacent to a gathered one within FORM_RANGE + 1 — a three-deep clump is gathered, a column past its third creep is not. Alone it failed: gate 130/131 (m34 camp from the enemy destroyed at 237 to 17 053:23 958), entry table +20 worse 19 / better 7, +50 13/13 — the strict gathering earns its keep against a MOVING line. Restricted to a still target (all his armed within ENGAGE_RANGE + RANGED_RANGE of the van unmoved for CHASE_WINDOW): gate 131, entries 1/1, families split 6-2 spread 5-1 tour 7-1 against 5-3/6-0/8-0 — a parked farmer single is "still" too. Restricted to a still BLOCK (`USE_FORM_CLUMP_STILL_ONLY` + `USE_FORM_CLUMP_GROUP`: at least SPLIT_MIN of them within ENGAGE_RANGE of each other), adopted: gate 131/131, entries 0 worse / 1 better (m32 brawl 11/0 → 14/0), split 5-3 → 6-2 (m31 22 085:24 306 → 24 313:14 978, m32 17 799:24 300 → 24 291:20 558, m35 to a loss), spread identical, tour 8-0 with his army broken where it used to stand (m29 his 5 alive of 14, m33 3, m34 4), blitz 6-2 → 7-1 (m30 4 336:23 480 → 23 421:19 516, m34 7 204:24 144 → 24 163:14 962, m28 a narrow loss). `USE_RACE_RANGED_GUARD`: under the race a ranged is not released while the core's ranged mass is below his largest group's ranged mass × PUSH_RATIO (the dry hunt had this guard; the race went around it). `USE_STALL_CORNERED_GROUP`: a still group of at least SPLIT_MIN of his armed (each unmoved a cell over CHASE_WINDOW, one of them within ENGAGE_RANGE + RANGED_RANGE of our strikers) weaker than the strikers by PUSH_RATIO cancels the "keeps its distance" stall — the corner match's form, told from a dispersal by the group's own stillness and size, so the gate's spread/farm/scatter lines (v132's rejection) do not move. The two together, without the clump: identical to v132 on the 26 entries and the 22 family lines (split m31 −3.7 k inside a lost line) — the stand has no compact tourer racing our ranged away and no cornered group; the series prices them. The v134 probe on top — the melee's swing to the adjacent armed melee before the focus order — is off with its numbers: gate 131 but entries +20 worse 14 / better 9, +50 15/7, outcomes 2 better / 5 worse (nine m31 and m34, wing m7 from destroyed to a points lead).


**Matches 487–506 — v133's twenty (08.09.2026, night): 14:6, 1 306 → 1 291, a different draw and an old class.** By his bot: Coldkimchi#1 3-1 (+16 — before this series 3-16 against him over the season, 1-2 in v131's twenty), MetalicaX#4 2-1, MetalicaX#6 2-0, けろびー#12 1-0, けろびー#4 1-1, 欢欢#7, Xeon_Lagunas#3, arukuka#1, houshanyun#1 and けろびー#3 1-0 each; the losses けろびー#1, けろびー#2, MetalicaX#8 (three blobs), Coldkimchi#1, MetalicaX#4 and けろびー#4. CPU as before (no timeout, the slowest warm tick 57 ms). Five of the six losses are lost FIGHTS of one shape, and three of them are opening wipeouts the v131/v132 series never met because they never drew these bots: MetalicaX#8 14:0 by t=165 (contact at 39, both armies on D5 at once), けろびー#2 14:0 by 142, けろびー#1 14:1 by 166, then MetalicaX#4 twelve on nine at 670–730 (six of ours for one of his) and Coldkimchi#1 at the edge (78–303, 14:1). In every one his melee out-swing ours by three to ten (82/85/48/35/55 against 21/8/11/9/16 in the first hundred ticks, adjacency 131/101/55/37/55 against 37/42/21/15/23), his melee reach our ranged and healers with our nearest melee five and more away, and our melee's first idle reason in `why-sum` is `!inLine` (31/33/38/21 of 35–49 idle ticks at t=100) — the formation gate, a rule of ENTRY (match 4: nobody enters the enemy's range before the share gathers within two of the van), re-evaluated every tick of the contact, where the van changes with every step, the gathering flickers and the patience resets on every gathered tick (v120e), so a melee already at two or three of his creeps under fire is "not in line" and does not lunge. The ghost A/B on the three records (v133 and v132 builds, `ghost-ab.sh`) is byte-identical — the same contact ticks, the same exchange — so v133's cuts did not make these; the ledger's `melee adjacency` (45 % of losses, 0 % of wins) and `entry lost` rules had named the class since 06.09. The sixth loss is the quiet tourer again: けろびー#4 with his eleven in one group, the quiet chain released five of ours at t=988 (the pool by power — the ranged first) with his group 117 ticks away, the core "fell under 0.97" and three were recalled within 26 ticks, and he held all seven flags at t=1000 (rate 0/25) — v133's ranged guard stood only under the race. The two forms this names went to the stand as v134 and both are off with their numbers. The formation gate waived in the fight: as "an enemy within three or in the fire field" — gate 131, entries +20 worse 10 / better 13, blitz 7-1 → 5-3 (m28 5 890:23 972, m30 5 956:22 619, m33 3 817:24 121), split 6-2 → 5-3, tour m33 his 3 alive → 12: a farmer's picket at three lifts the gate and the melee dance with it instead of marching, which is what the gate is for; as "only a creep hit last tick" — entries +20 worse 7 / better 2, split m28/m32 to 15 994:24 297 and 14 859:24 290, blitz m31/m32 to 9 584:23 893 and 9 199:23 790 — the picket's fire pulls the melee out of the march the same way. The ranged guard against a compact largest group: at four — split 6-2 → 4-4 (m28, m32 to losses), all else identical; at two thirds of his armed — identical everywhere but split m32 (24 291:20 558 → 23 651:24 298). So the entry class is open with its shape measured (his melee reach our ranged and healers with our nearest melee five and more away in 61 of 114 adjacent pairs against MetalicaX#8), and the whole-army tourer's release is one match of a 1-1. The played build stays v133.

**Matches 507–516 — the day of fame on v133 (08.09.2026): 7:3, 1 291 → 1 268, and the class named on five records.**
Ten fame games: six wins in a row (あぶらむし#12/#10/#13, raznikk#6, stachu3478#3, System), then three losses to
MetalicaX — **#10 twice, at t=100 and t=200** and #3 on points — and a win over #4. The chest walked 6 → 1 (a win is
+1, a loss −2) and was taken at level 1: 12 Key Fragments, 1 Tenacity (a fame modifier: +1 level on a draw), 10 Orange
Paint. The two MetalicaX#10 wipeouts are the same class as the three of v133's twenty (MetalicaX#8, けろびー#1,
けろびー#2), so the class now has five records, and read together they are ONE CHAIN, not five accidents. His fire goes
at our HEALERS: 100 shots of 287 (6a9fa63e), 83 of 236 (6a9fa64c), 89 of 266 (6a9f2c45), 59 of 338 (けろびー#2) — 17–35 %
of the volley; ours into his healers are 0, 2, 1 and 17 of 86, 82, 130 and 187 — 0–9 %. Next link: healing stops
GIVING PARTS BACK, and our creeps strip — a melee carries its eight ATTACK first in the body, so 600 damage takes all
of them, and at 1 034 hits of 1 600 three of eight are alive. A stripped creep walks to the NEAREST healer, and in a
fight the healer stands at the front (`engagedNear → fighters`: in a fight he does not count the stripped as a patient
and never comes to them), so the wounded walks FORWARD, into the focus: **178 of 196 creep-ticks within three of his
armed against his 40 of 44** (6a9fa63e), 155 of 186 against 32 of 58 (6a9fa64c). The end of the chain is the swings:
his melee adjacent 143 creep-ticks against our 20, swinging 145 against 20 (6a9fa63e), 82 against 20 (6a9fa64c), 48
against 21 (6a9f2c45), 51 against 8 (けろびー#2). What holds ours back is `!inLine` 56 creep-ticks and `rotating` 34 of
62; in けろびー#2 melee_4 walks between (49,47) and (52,48) for thirty ticks with `slot`, `giveup`, `rotating` while an
enemy stands two cells away, and never swings once. And all of it is decided in the first twenty ticks of the entry:
three deaths of ours to his none, 14 swings to his 43 — after which nothing changes, and he ends these matches without
losing a single creep.

**v134 — the stripped walk to a healer BEHIND them, and four probes rejected with their numbers.** The one change that
plays: a stripped or rotating creep goes to the nearest healer standing FARTHER from his armed than itself
(`USE_HEAL_BEHIND`), so the step towards healing stops being a step towards the front; walking it out of the fight
altogether is wrong, because healing gives parts back and a healed creep picks its weapon up again. The stand cannot
see the rule — gate 131/131, entries +20 worse 7 / better 7, +50 worse 9 / better 8, the split/spread/tour families and
blitz identical to the digit — and a new counter says why: `stripped:` in `run.mjs` (see the harness README) shows that
across twenty scenarios **the stand strips HIM, not us** (ours 0–28 creep-ticks against his 0–255). The counter was
added because no rule about our stripped creeps can be priced without it; the stand also gained two cuts of the live
blob, `+heals` (his fire at our healers first) and `+deep` (his melee drawn to a soft target from five cells, through
the line rather than at it). Both play; neither turns the exchange — in fifteen of sixteen runs the stand still strips
him and his army still dies. The live blob is not reproduced, and that subject is now in the INSTRUMENT, not the bot.
Four probes were rejected: **his healer as the first focus tier** (`USE_FOCUS_HEALER_FIRST`), twice — "any killable
healer above the ranged" gave entries +20 worse 11 / better 9, +50 worse 15 / better 7 and split m34 21 210:24 100 →
15 365:24 095; "only the healer the target does not die because of" (within HEAL_RANGE of an unkillable candidate) gave
10/7 and 11/9 and turned blitz m29 from a win 23 954:23 223 into a loss 4 413:23 964 — the reason has been in the code
since v9: **the stand does not shoot healers at all** and holds its own adjacent, so firing at his healers here is pure
lost tempo while live it is a third of his volley; **the front is not thinned by rotation**
(`USE_ROTATE_KEEP_FRONT`) — the exit is refused while our armed at the contact would drop below his — gate 131/131 but
entries +20 worse 10 / better 6 and tour m28 23 298:19 604 → 20 938:23 289, a fighter held at the front with a quarter
of his weapon catching the focus instead of swinging; **a sticky cornered group** (`USE_CORNERED_STICKY`) — the state
flickered 154 times in match 6a9fa6ad and `keepsDistance` with it, "stand" and "go" on alternate ticks — holding it for
eight ticks took split m35 FROM A LOSS 18 717:24 303 TO A WIN 24 303:22 567 and blitz m28 from 23 613:23 978 to
23 935:16 456, but dropped the gate on match30:camp (19 645:23 689), and at four ticks the gate falls the same way
while blitz m28 and m30 go to 4 780:23 951 and 4 245:23 484. The flicker stays open: the cure looks to be in the
condition itself, not in giving it a memory. The opening against けろびー#12 was deferred by price — over forty matches
only the blobs drain rating (MetalicaX#10 −22, けろびー#2, MetalicaX#8 and けろびー#1 at −10…−13), and けろびー#12 is not
in the leak list at all.

**Matches 517–536 — v134's twenty (08.09.2026): 6:14, 1 268 → 1 172, and the live series rejects the rule the stand
could not price.** Against v133's 15:5 and −20 on the same day, v134 lost 96 rating in twenty matches. The draw changed
under us — MetalicaX#9 and #11 are new bots (0-3 and 0-1 against them), ricardo18informatica2020#4/#6 and raznikk#19
are new and beaten 4-0 — so the reading is per HIS BOT, and the rows with both versions in them are what count:
**Coldkimchi#1 3-1 (+16) → 0-3 (−18)**, MetalicaX#4 3-1 (+8) → 0-1 (−9), MetalicaX#3 0-1 → 1-2, けろびー#4 1-1 → 1-1,
MetalicaX#10 0-2 → 0-3. Two rows where v133 won are lost outright, and one of them is the bot v133's own cycle had
turned around, so the regression is real and not a draw effect.

The mechanism is in the three Coldkimchi losses, and it is not the rule's target but its second group. All three carry
`entry lost` — 4 226:1 326, 3 522:1 514 and 3 218:1 728 hits over the first twenty ticks of contact — and in all three
our armed ranged had a target within three about HALF as often as his (26:56, 45:66, 34:49 creep-ticks), where v133's
three wins over the same bot carried neither diagnosis at all. `USE_HEAL_BEHIND` applied to `wounded || rotating`, and
a ROTATING ranged is not stripped: it can still shoot. Sending it to a healer standing farther back took it out of
range and silenced it — the entry was lost by the guns that walked away from it. On the rule's own group the number
moved the right way wherever a healer behind existed at all (stripped within three of his armed 176 of 803 creep-ticks
against 88 of 88 in a match where none did), so the subject is right and the price was being paid by the wrong creeps.
The rule is off; narrowing it to `wounded` alone is an open hypothesis that only another series can price.

What the twenty say beyond the rule: the class is unchanged and now costs more, because MetalicaX has three bots doing
it (#9, #10, #11 — 0-7 between them). Every wipeout is the same chain read on 07–08.09, and the two subjects it leaves
are both upstream of any target-choice rule — the stand's blob never wins an entry against us, and live we stand at the
centre post from t=3 while his blob arrives at t=40 whole. In three of the four wipeouts read in detail we had ZERO
points at t=100 while holding that post, so the post is not paying for the fight it costs.

**The post cut (08.09.2026): the post pays, the OCCUPIED flag is the strongest split in the store, and the parity gate
on capture is not convicted.** The suspicion this cut was built to test — that standing at the centre post costs a
fight and returns nothing — is WRONG, and the store says so over 295 finished matches whose first hundred ticks we
hold. Posted on the centre flag before contact against not posted: 102-46 (68 %) against 102-42 (70 %) — the outcome
does not move — but our score at t=100 is 221.7 against 102.9 and his is 300.8 against 414.9. The post is worth roughly
double the points and takes about a hundred off his; on the recent builds it is 63 % against 64 %, the same picture.
So the doctrine stands as it is.

What the same cut found instead is the sharpest split any instrument here has produced. Among the matches posted on the
centre, whether a creep of OURS is standing ON a flag at t=100 divides them 80-20 (80 %) against 23-26 (46 %), and the
scores by then are 330.6 against **4.8**. Points come from an OCCUPANT, not from guards: in the wipeouts we stand
twelve creeps around a flag printed `D50g12` — nobody's, no occupant, twelve guards — and take nothing. Flags are
occupied by the RUNNERS (the two pure-MOVE scouts), and in 6a9fa63e scout_1 spent 37 ticks at (85,80)…(85,50) with the
flag at (85,49), one cell away, `step=stay`, mode POISED — `captureAllowed` (the parity gate) had refused the capture.

Whether that gate is the cause is NOT established, and the honest control says so. Never poised adjacent to a free flag
gives 82 % and 301 points; poised at all gives 64 % and 112; poised ten ticks or more, 66 % and zero points. But the
poised matches are almost entirely against Coldkimchi#1, MetalicaX#2/#3/#4/#8 and けろびー#1/#2/#3, and the never-poised
ones against ricardo18informatica2020#4/#5 and けろびー#4/#12 — the strong bots fight AT the flags, which is what makes
the gate fire. Held fixed by his bot the gap survives pooled (poised 48-25, 65 %, against never 52-9, 85 %), but the
"never" cell inside each strong bot is one or two matches, and inside けろびー#4 the sign reverses (poised 4-1 against
never 11-8). So: the occupant matters, the parity gate is not convicted of costing it, and separating the two needs an
experiment rather than another cut of the same records.

The cut is kept as `tools/flagcut.py`, so these questions can be re-asked whenever the doctrine is touched: it reads
the first hundred ticks of every finished match in the store and prints the post cut, the occupant cut, the poised cut
and the opponent-held-fixed control (`--builds v133` restricts it to given builds). It is the store-wide counterpart of
`tools/autopsy.py`, which reads one match, and of `tools/ledger.py`, which adds up diagnoses.

**Matches 537–546 — ten diagnostic matches on v133 (08.09.2026): 6:4, 1 172 → 1 172, and the regression is confirmed
to be ours.** After v134's 6:14 the question was whether we had got worse or the draw had (MetalicaX had put out three
new bots in a day). Ten matches on the reverted build answer it: v133 holds the rating exactly level over ten — a
balance of zero against v134's −4.8 a match — beating けろびー#4 twice, MetalicaX#4 and #6, ricardo18informatica2020#4
and raznikk#10, losing to MetalicaX#4 twice and to the two new blob bots #9 (t=100) and #11 (t=200). So the −96 was
the heal-behind rule, not the field, and the class of loss is unchanged: MetalicaX's blobs still wipe us inside two
hundred ticks, and nothing in this cycle has touched that.

**The capture experiment (08.09.2026): the gate is acquitted, and the flag cut's sharpest split is reverse causation.**
The cut left one suspect — the parity gate refusing captures while a runner stands beside a free flag — and the way to
convict or clear it was to find out WHICH of the five gates fires. `captureAllowed` was refactored to name its reason
(`captureBlock`, the runner line now prints `POISED:<gate>`; the stub gate is identical to the digit, so the refactor
is inert), and ten matches were played to collect them. The answer is not the parity floor at all: of the runner
creep-ticks refused beside a flag that was nobody's, **rush 24, parity 5, contact 5** — it is the "a fight is twenty
ticks away" veto, and the parity floor barely appears (the five it does are end-of-match, our power already at zero).

And the veto is right, because of how this arena's flags work: a flag DEBUFFS ITS OWNER. `stackMul` in the bot says
−20 % attack or ranged, −25 % heal, +10 % damage taken per flag held, and the live `effects` line confirms it — at
t=409 of match 6a9fc813 ours read `A×0.8 R×1 H×0.75 D×1` while his read `A×1 R×0.8 H×1 D×1`, each side wearing the
penalty of what it holds. Capturing is buying points with strength, so taking a flag twenty ticks before contact means
entering the fight a fifth weaker — exactly what the veto prevents, and exactly what annihilation (a loss at any score)
punishes. Its price is negligible besides: 24 creep-ticks of waiting across ten matches, worth about a hundred points
in total where matches end at twenty thousand.

Which means the 80-20 against 23-26 of the occupant cut is REVERSE CAUSATION, and should be read as such: an army that
is winning its fight can afford to pay strength for points, so it holds flags; an army being wiped cannot, and does
not. The flags are a symptom of the fight going well, not a cause of it. The doctrine stands unchanged on both counts —
the post pays in points, and the capture gate earns its refusals.

**The stand gets the live arrival (08.09.2026): `+early`, and the limit it exposes.** Four cuts of the live blob have
now been built and priced — `+heals` (his fire at our healers first), `+deep` (his melee through our line to a soft
target five cells off), `+far` (his ranged at five, not three) and `+early` (his army at the CENTRE FLAG at full speed,
no formation rule, no feint, until our armed are within eight or it has arrived). The first three describe how he
fights and none moved the exchange. `+early` describes WHEN he arrives, and it works as intended: contact moves from a
mean of t=96 to t=58, landing on the live 40–44 on four of the eight fight maps. But the exchange stays ours — in all
twenty-four runs his army dies, and our losses over the first twenty ticks stay far under his where live they are
above. The gap is not a habit of his we failed to copy; it is that the stand plays a DESCRIPTION and MetalicaX plays a
bot. `+early` is nonetheless the form to price entry rules with, because it is the only one that puts our line against
his whole army before ours is formed. The stub gate is identical to the digit with every new form off (131/131).

**Unrated test games against one chosen bot — the instrument this whole cycle was missing (08.09.2026).** Every probe
against the blob class has been priced either by a stand that cannot reproduce the fight or by a live series that costs
rating (v134's twenty cost 96). The client has a third way and it was never used from here: its tests page posts
`arena`, `code` and `codeId` to `/api/test/start`, playing ONE game against ONE bot of his, and `/api/test/codes/<arenaId>`
lists who can be picked — a `system` bot, `recent` (the opponents of the last rating games, each with the code version
it played) and `favorites`, pinned with POST `/api/test/codes/<arenaId>` `{codeId}`. Measured: a game against
MetalicaX#10 finished in 258 ticks, `ratingHistory` came back empty and the rating stood at 1183 before and after, and
the match lands in the store like any other — `autopsy.py`, `series.py` and `replay.py` read it unchanged. `recent`
currently carries MetalicaX#10, MetalicaX#11, けろびー#4 and Coldkimchi#1, which is precisely the set that wipes us.
`tools/play.py` gained `--test 'Name#version' -n N` and `--test-list`; the payload POST retries, since the page throws
`Failed to fetch` on it more readily than on a GET. From here on, a probe against the blob class is measured against
the blob itself, for free, before anything is spent on a rating series.

**The blob campaign on unrated test games (08.09.2026): a baseline, five probes, and the mechanism narrowed — but no
win yet.** With `--test` costing no rating, the class was attacked directly. Baseline over 24 test games on v133:
**MetalicaX#10 1-5, MetalicaX#11 1-5, Coldkimchi#1 1-5, けろびー#4 4-2** — three of his bots are the class, the fourth
is the control. Every loss is the same shape: 100–200 ticks, our first death at t=46–49, his none.

What the games measure, and it corrects the earlier reading. Ours against his over a 200-tick loss: melee alive **50
creep-ticks against his 664**, swings **14 against 85**, shots 87 against 155, mass attacks 21 against 60, healers with
a wounded mate within one **60 creep-ticks of about 600 against his 129**, heals 85+12r against 132+65r, expected
damage 7 350 against 20 870. Both sides' `uptime` reads 100 %: ours did whatever they COULD, every tick they could. So
our melee do not mis-aim — **they die**: one that walks into twelve creeps takes about 750 a tick (five ranged and four
melee all reach it) and lasts two or three ticks, and its death is what opens the back rank. And the debuffs are HIS,
not ours — at the end of these matches he reads `A×0.6 R×0.6 H×0.5 D×1.1` from the flags he took while we read
`A×1 R×1 H×1 D×1`; the flags come after the wipe, so the entry itself is fought at 4 087 against 4 087, and our power
falls to zero in 42 ticks.

Five probes, each measured against both blobs (6 games apiece) with the stub gate as the control on everyone else. All
five are off with their numbers, the toggles and the reasoning kept in the code:

- **melee guard, at the mate** (`USE_MELEE_GUARD`): a melee with no target goes to the creep of ours his melee is
  closest to. 1-5 / 2-4, gate 130/131 (match34:camp) — and adjacency did not move (80:14 against the baseline's 84:20):
  standing beside a healer is not reaching the creep that hits it;
- **melee guard, at the enemy**: same rule aimed at his melee entering our back rank instead. 1-5 / 1-5, gate 130/131
  (match12:twelve, our army destroyed);
- **his hitting melee in the first focus tier** (`USE_FOCUS_MELEE_IN_CONTACT`): `ranged first` (v60) outranks the threat
  tier unconditionally, so a melee of his hitting our healer for 240 a tick is never shot while any ranged of his is in
  range. Gate 131/131 and **0-6 / 0-6** — moving fire off his ranged is worse, his melee cannot be killed while his
  healers stand;
- **no charge into a closed blob** (`USE_MELEE_HOLD_VS_MASS`): while his armed are a closed mass and we have no local
  advantage, our melee hold at MELEE_HOLD_RANGE instead of charging. Gate 131/131, 1-5 / 2-4 — noise;
- **healers placed by whom they can heal** (`USE_HEALERS_COVER_OVER_SAFETY`): against a blob every cell beside a wounded
  creep has his melee next to it, so the `meleeNear == 0` tier sends our healers where nobody needs them. Gate 131/131
  and **0-6 / 0-6** — a healer that sits by the wounded inside a blob dies itself, and the heals go down, not up.

What this narrows: the class is not a target-choice defect, not a placement defect and not a charge-timing defect —
three separate readings of "our melee are in the wrong place or hitting the wrong thing" were all tested and all
failed. What survives is the arithmetic: at first contact his twelve concentrate about 750 damage a tick on whichever
of ours is nearest, and nothing in our fight rules changes who is nearest. The next thing to price is therefore the
approach itself — how our army meets a closed mass at all — and `+early` is the stand form built for exactly that.

**Seven probes, 72 unrated games, and the class still stands (08.09.2026, second half).** After the first five probes
the campaign continued into the approach itself, which is where the wipeouts are actually decided. Two more readings
were built and measured, and two written earlier were finally priced:

- **the give-up is about a chase, not defence** (`USE_GIVEUP_LIFTS_ON_BACK`) and **a rotating melee still guards the
  back rank** (`USE_POKER_WHILE_ROTATING`), together: `poker` (v43) is the rule for exactly this class, and it is
  skipped both for a target our melee once gave up on and for any melee in rotation — 94 and 114 tags across the twelve
  baseline losses. Gate 131/131, **1-5 / 1-5**, the baseline to the game;
- **"a far rush" must mean FAR** (`USE_RUSH_FAR_NEEDS_RANGE`): the wipeouts are settled before contact — `rush=true`
  from t=10, `posture=HOLD` on the post until t=39, contact, our power at zero by t=81, and after contact
  `retreatFeasible` is false forever, so there is no later moment to decide. The bot SEES the unflagged rush and
  refuses to evade it, because v100's `rushFar` cancels the evade on STRENGTH alone (`theirs < ours * 1.15`), which at
  parity fires at any distance. Requiring his centroid to be farther than EVADE_RANGE restored the evade — `EVADE`
  appeared at t=57 and t=73 where it never had — gate 131/131, and the result was **1-5 / 0-6**;
- **the evade held steady** (`USE_EVADE_STICKY_RUSH`), since the first cut flickered EVADE→HOLD→EVADE: gate 131/131,
  **1-5 / 1-5**, again exactly the baseline.

So evading works mechanically and does not help: at equal speed there is no escape from a closed blob, which is what
the code has said since matches 11–12 ("running from it at equal speed is being caught with a stretched tail") and
what these games confirm. All seven probes are off with their numbers.

**What the campaign establishes.** The class is not a defect of target choice, of healer or melee placement, of charge
timing, of give-up bookkeeping, or of the decision to accept the fight — each was stated from state, gated, and priced
on the blob itself. What remains is the arithmetic nobody has moved: at contact his twelve concentrate about 750
damage a tick on whichever of ours stands nearest, our creep dies in two or three ticks, and its death exposes the
next. Every rule tried so far changes what our creeps DO; none changes WHO IS NEAREST — that is decided by the shape
our army meets him in (`planFight`/`planBlock`), and that is the one place left to look. The played build is v133,
unchanged, gate 131/131.

**v135 — the kite at two cells, the first thing in eight probes that moves the class.** The arithmetic that survived
every earlier probe named its own cure. His melee carry the damage that kills us — four creeps at 8 ATTACK are 960 a
tick in contact against his five ranged at 300 — and a melee reaches ONE cell while a ranged reaches three. So at a
standoff of two his four melee contribute nothing, our five ranged and his five trade evenly, and an even ranged trade
is not a 14:0. While his armed are a closed mass (`enemyMassed`), every creep of ours not already adjacent to something
takes the nearest of his melee as its target with a standoff of MELEE_HOLD_RANGE, avoiding fire; melee still swing at
whatever closes to one cell and `poker` still answers anything that reaches the back rank.

Measured on unrated test games, six per bot per run, gate 131/131 throughout: **MetalicaX#10 5-7 against a baseline of
1-5**, **Coldkimchi#1 2-4 against 1-5**, **けろびー#4 4-2 — exactly the baseline, so the control is intact**, and
MetalicaX#11 2-10, unmoved. Over the three blob bots that is **9-21 against the baseline's 3-15**. Two variants were
tried and rejected with numbers: a standoff of three drops the gate (match31:camp 10 131:14 582), and letting our
ranged break an already-started contact gives 0-6 / 0-6 — a ranged stepping out from under a melee stops shooting and
drags the line with it, while his melee follows anyway.

This is not yet regular winning against blobs — 30 % against 17 % is a real move, not a solved class — and MetalicaX#11
in particular is untouched. But it is the first cut that changes WHO IS NEAREST rather than what our creeps do with
whoever is, which is the direction the previous seven probes narrowed the search to.

**Matches 583–602 — v135's twenty (08.09.2026): 16:4, 1 183 → 1 228, and the class is broken open.** The confirmation
series for the kite, against v133's two diagnostic tens of 6:4 and 6:4 (a rating that ended exactly where it started)
and v134's 6:14 at −96. By his bot: ricardo18informatica2020 4-0, けろびー#12 3-0, けろびー#4 2-1, Coldkimchi#1 1-1,
and — the two that matter — **けろびー#2 beaten** and **MetalicaX#11 beaten**, both of which had wiped us at 14:0 under
v133 and neither of which the test games had moved. The four losses are MetalicaX#9 (t=100, still the old wipeout),
Coldkimchi#1, MetalicaX#3 and けろびー#4.

So the kite holds up where it was measured and beyond it: the unrated games said MetalicaX#10 5-7 and Coldkimchi#1 2-4
with けろびー#4 unchanged at 4-2, and the rating series turned that into 80 % over twenty against a full draw. What is
NOT solved is the fastest form of the class — MetalicaX#9 still took us in a hundred ticks — so the wipeout is now a
minority case rather than the rule.

**Chasing stability against the blobs: four more variants, all rejected, and what the diagnostics found (08.09.2026).**
v135's kite wins the series but not the class — MetalicaX#11 stayed at 1-5 in test games and both #10 and #11 wiped us
again in a refresh series (t=100 and t=200). Four variants were built and priced:

- **the kite as a BOUNCE** (trigger at two, step back to three) instead of a standoff: 0-6 / 1-5 against 5-7 / 2-10.
  A `standoff` pulls as well as pushes, and the diagnostics (`kite=` in the tick line, added here) showed why it
  matters — with the standoff form `kite=0` in six samples of ten while `massed=true` throughout, i.e. the rule fires
  seldom and mostly on creeps already at distance. Making it a pure bounce is WORSE, so the pull is the useful half:
  it draws our creeps to two, where they shoot, instead of leaving them far and idle;
- **healers and wounded kite too** (`USE_KITE_HEALERS`): 3-3 / 1-5, the same as without, and the gate drops
  (match31:camp 15 878:19 795) — a healer keeping its distance stops reaching its patient;
- **the kite distance follows his FAN** (`USE_KITE_MASS_AWARE`): mass attack hits within three at 10/4/1 per part, so
  standing at three costs him six damage instead of sixty; MetalicaX#11 throws 63 fans a match against #10's 37 and our
  12, which is exactly what distinguishes the bot the kite could not move. Holding three when clumped, two when alone:
  1-5 / 1-5, gate 130/131. Our own fire thins faster at three than his fan does — a single shot carries full damage to
  three while the fan there is already spent, so the trade is paid by us;
- **a standoff of three flat**: gate 130/131 (match31:camp), rejected earlier for the same reason.

What the diagnostics leave: the kite fires on few creep-ticks and only before contact — once his melee is adjacent the
rule steps aside, which is why his melee still stand adjacent 79–85 creep-ticks against our 16–22 even in v135's
losses. Making it act IN contact was tried (`USE_KITE_BREAKS_CONTACT`, 0-6/0-6) and fails because a ranged that steps
out stops shooting. So the remaining question is not the distance but the moment: what should a creep do on the tick
his melee becomes adjacent, when stepping away is worse than standing and standing is what kills us.

**Three more variants, and the discovery that half the earlier probes edited code the blob fight never runs
(08.09.2026).** Two rejections and one correction:

- **the helpless leave contact** (`USE_KITE_HELPLESS_OUT`): a ranged shoots while adjacent and a melee swings, but a
  healer or a stripped creep under a melee is pure meat — zero damage out, a free target held. 1-5 against MetalicaX#10
  (was 5-7) and 2-4 against #11 (was 2-10) — 3-9 against 7-17 together — and the gate drops (match31:camp). A healer
  that steps out stops reaching its patient, and the front loses its healing;
- **one wide line against a closed blob** (`USE_LINE_VS_BLOB`): `standoffLine` in `planBlock` is the only formation
  that makes us WIDE — the operator's own measurement on match 67 puts his nine armed at 9.7 cells across the axis
  against our 4.6 — and it was switched off whenever his melee were inside, i.e. exactly in a blob. Turning it on for a
  massed enemy: gate 131/131 and **0-6 / 0-6**. A single row is wider but also thinner: its melee sit on the flanks
  rather than in front of the ranged, and the blob walks into the middle where nobody covers it. v113 was not being
  careless when it excluded this case.

The correction matters more than either. A `plan=` counter added to the tick line reads **0/0 for the whole fight**
against a blob: `planNow = USE_PLAN && (standoffNow || standingNow)` and `standingNow` requires `!meleeBrawl`, so with
his melee inside it is always `planBlock` that runs, never `planFight`. Two earlier probes — healers placed by coverage
(`USE_HEALERS_COVER_OVER_SAFETY`) and everything reasoned from `healerCmp` / `rangedCmp` — were editing a planner this
fight does not call, which is why they read 0-6 and changed nothing measurable. Any future placement work against the
class belongs in `planBlock`.

**The last three variants, and where the campaign stands (08.09.2026).** Now aimed at `planBlock`, the planner the
blob fight actually runs:

- **the rear one row deeper** (`USE_REAR_DEEPER_VS_BLOB`): `planBlock` puts healers and wounded in row 1 — one cell
  behind the melee front — so his melee reaches them with the same step it uses to pass the front. Holding them at row
  2 costs healing (4 a part at two cells against 12 adjacent) but keeps them alive. Gate 131/131, **1-5 / 1-5** against
  v135's 5-7 / 2-10: a front healed at a third collapses faster than the healers are saved;
- **kite until our own guns are ready** (`USE_KITE_UNTIL_READY`), from a number in the entry telemetry — at first
  contact it reads `reach=2/5`, two of our five ranged with a target in range while he fires with all twelve, and by
  t=50 all five reach but the entry exchange is already lost (9 500–10 600 hits to his 5 800–6 500 over twenty ticks).
  The broad form (hold off whenever our guns are not up, any enemy) drops four scatter lines at once — there our ranged
  seldom reach and the army stopped advancing altogether. The narrow form (at a closed blob, hold three instead of two
  until two thirds of our guns reach) gives **2-4 / 2-4**: #11 comes up, #10 falls back, the sum is unchanged and the
  gate drops to 130/131 (match31:camp).

Sixteen variants have now been priced against the class on unrated games. One of them won — v135's kite, confirmed by a
20-match series at 16:4 and +45 rating — and the other fifteen are off with their numbers. Stability against
MetalicaX#11 is still not there, and the honest summary of every failure is the same: each rule changes what our creeps
do once his blob is on them, and the entry is lost before that, with two of our five guns in range against his twelve.
The next idea worth trying is not another rule about the fight but the MARCH — arriving with the army already inside
its own firing range of the contact point, which no probe so far has touched.

**The march probe, and what seventeen variants together say (08.09.2026).** The last untouched direction was the
approach rather than the fight: arrive with the army already inside its own firing range instead of feeding it in.
`USE_RALLY_BEFORE_BLOB` — while his massed army is not yet in contact and fewer than two thirds of our guns reach,
walk to the mass of our own rather than at him or into a slot. Without a proximity condition it dropped 29 gate lines
(six of his armed in a clump is ordinary, so the army gathered instead of playing the flags: army, camp, screen,
farm+weak, scouts, grab, all on points). With one (his centroid within ENGAGE_RANGE + RANGED_RANGE) the gate is
131/131 and the result **1-5 / 0-6** against v135's 5-7 / 2-10: the tick spent gathering hands him distance, and we
still do not finish gathering — he arrives faster than we form.

Seventeen variants have now been priced against the class, and read together they say something the individual numbers
do not. **Every rule that slows our closing makes it worse** — a standoff of three, a rear row deeper, one wide line,
holding off until our guns are up, gathering before contact — while the one that works is the smallest possible
retreat that keeps every gun firing (the kite at two). And every rule that speeds our closing or frees a creep to act
(breaking contact, the helpless leaving, healers by coverage) makes it worse too. The bot is on a ridge: against an
equal army fought at parity, both more caution and more aggression cost more than they save, and what remains is the
per-tick exchange itself, where he lands 20 000 expected damage to our 7 400.

That is not a rule to write; it is the opponent's micro play. The instrument that would let us copy it is the one the
stand could not build — his fight, tick by tick, from his side. The next thing worth doing is therefore not another
probe but reading HIS replay creep by creep through the entry: which target each of his twelve picks each tick, and
what our twelve would have had to pick to trade evenly.

**Reading HIS replay creep by creep — the sharpest asymmetry yet, and why fixing it did not help (08.09.2026).** The
entry trace of a MetalicaX wipeout shows his formation plainly: four melee abreast on ONE row — (48,46) (47,46)
(46,46) — with his five ranged on the row behind, and the whole army stepping forward together, row by row, until the
front melee makes contact. Ours on the same ticks have melee at y=55-56 and ranged at y=54, i.e. the ranged AHEAD.

Measured over the entry window of two losses (`tools/frontorder.py`, kept): **his melee reaches one of our soft creeps
first in 60 % of ticks; our melee reaches one of his soft creeps first in 0-10 %.** Our melee is the nearest in 2-3
ticks of 28, his in 15-19. His line covers its ranged and healers; ours does not. The bot has a rule for exactly this
(`behindMelee`, v69) — in `planFight`, which this fight never calls.

Two probes followed, and both failed in an instructive way. Pushing the planned ranged row back against a blob
(`USE_RANGED_ROW_VS_BLOB`): gate 131/131, games 1-5 / 2-4, **and the measurement did not move — 63 % against 60 %**.
That is the finding: a slot is a PLAN, and a creep in a fight walks to its TARGET (engage, poker, kite, prey), so
placement rules barely reach the battlefield at all. Editing the target instead — a ranged whose nearest enemy melee is
no farther than our nearest melee walks behind that melee (`USE_RANGED_KEEPS_BEHIND`) — gives 1-5 / 1-5 and drops the
gate: a ranged that hides behind a melee loses its target, since its range is three and the cell behind the melee is
usually outside it. Being covered costs it its fire.

Nineteen variants priced now, one of them kept (v135's kite). The asymmetry is real and measured; what is missing is a
way to be covered WITHOUT going silent, and neither placement nor a movement target has provided it.

**Twenty variants: the campaign's closing account (08.09.2026).** The last one was aimed at the gap the replay reading
left — cover without silence. A ranged may step out from under a melee only when TWO or more of his armed stand inside
its range, so a target is certain to remain after the step (`USE_KITE_KEEPS_FIRE`): gate 131/131 and **1-5 / 0-6**. The
condition does keep the target, but the step surrenders a cell of the line — the melee that was covering the ranged
follows it back, and the whole line gives ground. There is no way out from under a hit in this bot that does not cost
position: not through the movement target, not through the plan, not through distance.

The three axes are now all closed by measurement, and one of them by data that predates this campaign:

- **the moment** — evading a massed unflagged rush works mechanically (EVADE appears where it never did) and does not
  help: at equal speed there is no escape, as matches 11–12 recorded long ago;
- **the place** — fighting at a wall is already priced in the code's own comment: lost at 83, 92, 110, 273, 295, won in
  the open at 66, 75, 79, 93, 96. The edge does not shield a flank, it removes an exit;
- **the shape and the target** — nineteen probes across `planBlock`, `planFight`, the focus comparator, the give-up
  bookkeeping, rotation, the kite distance and the movement chain. One survived: the kite at two cells, and the
  measurement of WHY it survives is that it is the smallest retreat that costs no fire.

The one number that no variant moved: over a 200-tick loss his expected damage is 20 000 against our 7 400, his melee
adjacent 79–85 creep-ticks against our 14–22, and his melee reaching our soft creeps first in 60 % of ticks against our
0–10 %. Those are outcomes of his per-tick choices, and every rule tried here is an approximation of a choice. What the
bot does not have is a way to CHOOSE — to weigh, for this creep on this tick, what the exchange looks like two ticks
out. That is a different architecture (a short forward search over the exchange), not another toggle, and it should be
started deliberately rather than bolted on.

**v138 — a commander and a simulation: the architecture the operator asked for (08.09.2026).** Twenty rules had failed
because each approximates a per-tick choice, and because each creep decided ALONE: two would pick one cell, a third
would block a fourth, while his line walks abreast step by step — one decision for twelve. So the army now gets one.

`commandFight` assigns CELLS once a tick. Candidates are every passable cell within two of a fighter; each carries
`incNow` and `incNext` — the damage that reaches it now, and after his melee take one step, since a melee two cells
away steps in and swings. Melee are placed first where they reach his armed, ranged next where a target is in range
and the least is incoming, healers last within HEAL_RANGE of a wounded mate and out of fire. A cell is taken once and
creeps are served most-constrained-first, so ours no longer block ours. Attacks run in their own pass and are
untouched, so a creep already in place simply stands and hits.

The commander does not guess which shape is right: it proposes five (PRESS, HOLD, YIELD, FOCUS, KITE) and a four-tick
simulation picks. Inside it the enemy is played by the model the stub and the replays agree on — melee at our nearest
soft creep, ranged holding three, healers by the most wounded — and the arena's own arithmetic is modelled: mass attack
whenever a fan beats a single shot (10/4/1 by distance), healing 12 adjacent and 4 at range, and PART DECAY, since
armed parts stand first in the body and a creep at ten hits does not hit like a whole one.

Measured, in order: the commander alone 2-4 / 1-5 and gate 128; with the simulation choosing, gate 130 and the intents
finally differ (PRESS, HOLD, YIELD and FOCUS all appear in one match, where the first version only ever chose PRESS);
with part decay 1-5 / 2-4; with mass attack modelled 3-3 / 0-6; without the kite among the plans **1-9 against
MetalicaX#10**, because the commander had displaced from the movement chain the one rule that worked. Adding KITE as a
fifth plan restored the gate to 131/131 — and THAT version is not measured live: the server stopped accepting code
uploads after some 150 games today (a small POST still answers, one carrying the zip does not). Tuning that did not
survive: a weight for surviving bodies (240 → gate 128, 60 → 129), a rollout policy after the first tick (1-5 / 1-5),
a SPREAD intent (130 in two forms), depth 3 or 6 (129 each; 4 holds 131), and assigning only cells reachable this tick
(130 against 131 for two).

The played build stays v135 until the commander is measured — releasing an unmeasured architecture into a rating
series is how v134 cost 96 rating. Everything is committed and toggled; the first thing to do next is to switch
`USE_COMMANDER` on and run the test games the upload limit denied.

**What the literature says about this exact problem, and what came of trying it (08.09.2026).** A search of the
published work on RTS unit micro turned up four things worth writing down, and one of them was implemented and priced
the same day.

**Portfolio Greedy Search** (Churchill & Buro, 2013, *Portfolio greedy search and simulation for large-scale combat in
StarCraft*) is our problem exactly: instead of searching over actions, each unit is assigned one of a few SCRIPTS, and
the assignment is improved by hill climbing — unit by unit, try every script, keep the one whose playout scores best.
It beats Alpha-Beta and UCT on fights up to 50 vs 50 within a 40 ms budget; we have twelve a side and 100 ms, of which
the commander uses 20. Implemented as v139 and rejected by measurement: per creep it dropped the gate to 128/131
(m30:kite 0:21 899 with CPU at 3.8 ms, so the line was being torn apart, not starved of time), and per role cluster —
which the same literature recommends — the gate held at 131/131 but the games read **0-8 and 0-8**. The lesson is the
one the papers state plainly: a portfolio is only as good as its playout, and ours is four ticks with a simplified
enemy, which over-values mixed assignments. Worth returning to when the playout is better.

Narrowing the commander's trigger, found while chasing that failure, is a genuine finding of its own: it must not run
against an enemy that is RETREATING (the kite scenarios scored 0 while our army stood trading) nor against a camp at a
flag, and only in a real brawl (`theirMeleeIn`). That took the gate from 128 to 131 — but the games still came out
6-10 and then 1-15, so the trigger was a bug fix, not an improvement.

Three ideas from the same search remain UNTRIED and are the best leads for the next attempt:
- **target selection by DPS-to-health ratio** — kill the enemy whose removal most reduces incoming damage soonest. The
  bot has this term (`threatOf(it) / it.hits`) but it sits BELOW `ranged first`, `guns` and `killTicks` in `focusCmp`,
  so it almost never decides anything;
- **overkill avoidance** — spread fire once a target is already dead this tick. Nothing in the bot models this, and it
  is a pure gain: every shot into a corpse is a shot not fired;
- **Lanchester's square law** — an army's strength goes as the SQUARE of its numbers, so a small edge compounds. Our
  simulation scores linearly (a sum of profiles), which is exactly why plans four ticks apart look nearly identical;
  a quadratic score would separate them.

And from the Screeps community rather than academia: quads path as ONE unit — the group's route is computed from the
centre creep and every member moves to a point on that route just outside the group — and the formation is SWITCHED on
contact rather than carried into the fight. Our `TrafficManager` resolves collisions after the fact; computing one
route for the mass and hanging the creeps off it is a different and untried approach.

**Why the StarCraft algorithm did not work here — two real defects found, and the answer that it is not the legacy
(08.09.2026).** The operator's objection was right in principle: an algorithm that beats Alpha-Beta and UCT in
StarCraft should not be bad here, so the fault is likelier ours. Two faults were indeed found by looking.

**The order was checked against the engine and ours was backwards.** Screeps applies MOVEMENT LAST and resolves an
attack from the position BEFORE it — "by executing creep.move() and then creep.attack() in the same tick, the attack
still runs from the old coordinates". So a melee can strike AND step away in one tick, but cannot step in and strike.
Our simulation moved first and struck afterwards, i.e. it scored a fight that the game never plays. Fixed: strike,
heal, then move. (Also verified from the client's own definitions: RANGED_ATTACK_POWER 10, ATTACK_POWER 30, HEAL_POWER
12, RANGED_HEAL_POWER 4, mass attack 10/4/1 by distance — our coefficients were right — and damage runs through the
body from the START of the array, which is why armed parts die first.)

**The commander's order was being ignored by half the army.** `slotHold` ("a melee with an enemy adjacent stands and
hits") sat SECOND in the target chain, above the order — so in a blob all four of our melee ignored the commander from
the first tick of contact, while the simulation assumed they walked to their assigned cells. The plan was being scored
for an army that did not exist. Fixed: the order now outranks `slotHold`.

Both fixes are right and both were measured: portfolio search went from 0-8 / 0-8 to 3-13, and with the corrected
intent order to 4-12. Still short of v135's kite (7-17 over 24 games).

**So the legacy was tested directly and acquitted.** `USE_PURE_COMBAT` silences the ENTIRE old target chain while the
commander fights — a creep with an order either goes there or stands, and nothing else may speak. It scored **2-6 and
2-6: exactly the same as with the full chain**. The bottleneck is therefore not accumulated logic but the QUALITY OF
THE FORECAST: four ticks with a simplified enemy do not separate good plans from bad ones. A bot rewritten from
scratch on the same forecast would land in the same place; what has to improve first is the playout — modelling our
own combat rules inside it rather than "everyone walks to the assigned cell", or a longer horizon, or an evaluation
fitted to real outcomes rather than assumed.

**v140 — the commander switched ON, and the forecast made to predict the fight we actually play (08.09.2026).** Three
changes to the simulation, measured one at a time against MetalicaX#10 and #11:

- **the forecast now uses the bot's REAL focus target.** Ours in the playout used to shoot "the weakest in range" while
  the bot shoots a sticky focus target — so the plan was scored for a fight nobody would fight. Feeding the actual
  target in gave **3-5 and 3-5**, the best the commander has managed, and 10-22 over 32 games (31 %) against v135's
  7-17 (29 %). That makes the commander no longer worse, and it is now ON;
- **Lanchester's square law in the score.** Power is now `dps × hits`, not their sum: an army's strength goes as the
  SQUARE of its numbers, because each extra body both fires and absorbs. Measured 3-13 on its own — no better — but it
  is the correct form and stays;
- **overkill avoidance** (from the RTS micro literature: focus fire while spreading once a target is already dead)
  — 2-6 and 2-6 against 10-22, rejected: our focus target rarely dies within a tick, because three healers hold it,
  and spreading costs tempo.

**On the threat and power matrices, which the operator asked about.** The formula itself is RIGHT: `lanchester(dps,
heal, hits) = sqrt((dps − heal) × hits)` is the product of firepower and durability — the square law written linearly,
and comparing ratios is unaffected by the root. But a real defect sits next to it: `effectiveDps` counts a creep's FULL
profile wherever it stands, so the measure weighs POTENTIAL while only half the army is fighting — at first contact
`reach=2/5`, two of our five ranged have a target against his twelve, and the linear measure still reads 4 087:4 087.
By the square law twice fewer shooters is four times less army, which is exactly why we enter fights we think are even.
Counting power by participation was implemented and measured anyway: applied across the whole measure it dropped the
gate to 124/131 (hard cut) and 126/131 (soft), and scoped to the local-advantage decision alone it held the gate at
131/131 but played 1-7 and 3-5. So the diagnosis stands and the cure is elsewhere: made cautious this way, the army
stops entering fights it should enter and gives away points instead.

Control is intact: **houshanyun#1 6-0** with the commander on.

**Matches 617–636 — v140's twenty with the commander ON (08.09.2026): 13:7, 1 221 → 1 222.** The series was run
precisely because the commander had only ever been measured against blobs, and it answers the question it was asked:
on the FULL field the commander is worse. Thirteen wins to seven, a rating that ended one point above where it began,
against v135's 16:4 and +45 the same day. By his bot: けろびー#12 2-1, Coldkimchi#1 1-1, MetalicaX#4 1-1,
ricardo18informatica2020#4 2-0, あぶらむし 2-0, and けろびー#1 — one of the blobs that used to wipe us — beaten. But
MetalicaX#9 0-2 and #10 0-1, all three of those lost inside 200 ticks.

So the picture is consistent with the test games rather than contradicting them: against blobs the commander is level
or slightly ahead (31 % against 29 %), and against everyone else it gives away what the kite was winning — 65 % over
the field against 80 %. The commander is off again and v135 plays; the code and its measurements stay, and the one
thing that ever moved it — making the forecast predict the fight we actually fight — is where to continue.

### v137–v180 — эпоха командира (перенесено из комментариев кода, 13.09.2026)

Раздел собран из комментариев `PainAndGain.kt` перед их вырезанием: проверка нашла тридцать номеров версий, которые жили
только в коде и нигде в доках, — двадцать семь из них принадлежат эпохе командира (v137–v180), три стоят до неё (v103,
v129, v130 — пробы, отвергнутые стендом) и одна после (v212). Каждый абзац — одна версия или неразделимая пара: что
ввела, чем судилась, вердикт и какой тумблер несёт её сегодня. Числа — как в комментариях, без пересчёта; версия,
упомянутая в коде лишь вскользь, помечена так и здесь. Источники перечислены в конце файла построчно.

**До эпохи: v103, v129, v130 — три пробы, отвергнутые стендом.**

**v103 — доля смежности мили в мощи (пункт 5 плана оператора, «цена стрелков»; `USE_MELEE_ADJACENCY_SHARE = false`,
`MELEE_ADJACENCY_SHARE = 0.25`).** Модель считала удар мили целиком (четыре M8A8 — 960 в тик против 300 у пяти M6R6, три
четверти урона стороны), а по реплеям 238/249 мили бьёт, только когда вплотную: у него 20 % крип-тиков боя, у нас 5–8 %;
36 % его урона и 19 % нашего пришлись на мили. Отсюда цена флагов наоборот: R3 (−20 % стрельбы) стоил модели 1 % мощи,
A3 (−20 % удара) — 7,5 %, и скаут брал R3 первым в каждом матче, отдавая пятую часть того огня, которым бой решается
(открыто с матча 47). Правка: в счёте мощи удар мили входит с долей MELEE_ADJACENCY_SHARE, хиты мили — целиком, он
принимает огонь (r->melee 163 из его 361 выстрела); Ланчестер по-прежнему √(урон − лечение) × хиты. ОТВЕРГНУТО стендом
(v103 поверх v102): гейт 131/131, но 27 хуже / 40 лучше — перетасовка всего; spread 6/6 → 2/6 (m31 11058:24336, m19
17111:24313, m28, m33), camp+shy 5/5 → 3/5 (m30 17998:22529, m31 23274:24031), brawl m32 — наша армия уничтожена
(5219:14635). Цены флагов перевернулись (R3 из дешёвого стал дорогим), и все пороги паритета, настроенные под старые
цены, стали запрещать захваты, на которых стоят гонки стенда. Находка с числом: доля 0,25 требует перенастройки
PARITY_FLOOR/CAPTURE_FLOOR по всем семьям. Закрыто в v193 — та же доля, но ИЗМЕРЕННАЯ, а не назначенная
(`USE_MEASURED_MELEE_SHARE = true`): по реплеям против Coldkimchi#2 мили стоит вплотную 1,1 % крипо-тиков контакта у нас
и 2,3 % у него (в патовом матче 4,9 % и 0,5 %), то есть A3 почти бесплатен обеим сторонам, а R3 дорог обеим; измеряемая
доля на стенде сама поднимается к единице там, где враг идёт в контакт, и порогов не переворачивает. Ветка константы в
`powerOf` осталась как запасная.

**v129 — две пробы дебюта после серии 407–426 (матч 407, MetalicaX#4 на туре): пост к центру и «преследуемы только тем,
кто может дойти» (`USE_POST_TOWARD_CENTRE = false`, `USE_HUNTED_NEEDS_REACH = false`).** Первая: пост под безфлаговым
броском — флаг нашей половины, ближний к центру: армия ждёт конца удержания в 25 от D5 у A3, а не в углу у R3; первый
флаг по времени тот же, второй на тридцать тиков раньше (матчи 5, 8, 19 серий 367–406: его шесть флагов к 80–91-му, наш
второй на 83–140-м). ОТВЕРГНУТО стендом: blitz 4-4 → 3-5 (m31 23931:21418 → 8049:23597, m32 23975:21586 → 4379:24104),
таблица входов по +50 9 хуже / 4 лучше (brawl m28 486:13518 → 5126:10606, m35 2632:12727 → 5868:4741), split m28 и m35
из побед в поражения; лучше только tour (m28/m31/m32). Армия, ждущая ближе к центру, встречает бросок и группы блица
раньше и хуже; это четвёртая проба дебюта, и все четыре стенд оплачивает боями. Вторая: его вооружённый центроид дальше
HUNTED_FAR (= EVADE_RANGE + APPROACH_WINDOW) при темпе сближения ниже APPROACH_RUSH — не hunted, и цель-флаг не
снимается ради уклонения. Матч 407: с 1269-го FLAG на один тик (его H4 в (8,90), travel 43) и EVADE следующим — по одной
силе 3559/3044 = 1,17 при его армии в 47 клетках и approach=0; 530 тиков на R3, два бегуна без дела, отставание
1750–3250 досягаемо, проигрыш у предела. ОТВЕРГНУТО стендом в трёх срезах на сценариях с армией без одного мили
(OURWEAK=1 — форма матча 407; без неё проба на 26 + 8 + 22 сценариях не срабатывала ни разу): tour 5-3 → 6-2 во всех
трёх, но split 7-1 → 3-5 (центроид), → 4-4 (только при проигранной гонке, lostRaceNow), → 4-4 (ближайший его вооружённый
вместо центроида) — без «преследуемы» армия идёт за флагами в группы фермера, которого уклонением обыгрывала по очкам;
на tour m28 (9246:17774 в третьем срезе) переключение hunted на границе сбрасывает уклонение и даёт точку ухода там, где
стационарное состояние её не находило. Ради этой пробы в стенде появился ключ `OURWEAK=N`
(`tools/stub/painandgain/README.md`): стенд никогда не стоит нам крипа, и правило о слабости против далёкой праздной
армии без него не срабатывало ни в одном сценарии. Предмет 407 остался открытым.

**v130 — свободный флаг бегуна (`USE_RUNNER_FREE_FLAG = false`).** Вето запаса выхода не касается бегуна без оружия,
если его ближайший угрожающий крип дальше от флага, чем путь бегуна плюс порог бегства (SCOUT_FLEE_TRIGGER = 8): бегун
дойдёт раньше, чем угроза войдёт в его порог. Матч 407: армия 530 тиков в EVADE при его армии в 47 клетках, бегун
scout_1 всё время RESERVE — у каждого флага запас выхода отрицателен или неизвестен; бегун — M1 на 100 хитов, армию не
тянет. ОТВЕРГНУТО стендом на сценариях OURWEAK=1: tour 6-2 → 6-2 (m31 в победу, m32 в поражение, m30 23983:14925 →
23969:23514), split 4-4 → 2-6 (m28, m33, m35 в поражения — бегун идёт на флаг между группами фермера и отдаёт его вместе
с собой), blitz без изменений, семейства почти без изменений. Вторая проба предмета 407 после армейской (v129, split 7-1
→ 4-4); предмет открыт.

**Эпоха командира: v137–v180.** Версии между теми, что названы, — v138 (симуляция размена и замыслы), v139, v140, v141,
v144, v155, v157, v158, v159, v178 — в доках уже есть или упомянуты здесь только там, где без них не читается соседняя.

**v137 — командир (оператор; `USE_COMMANDER = true`, карта `commandOf`).** Одно решение на всю армию вместо двенадцати
самостоятельных: каждый крип решал сам, и двое выбирали одну клетку, третий загораживал четвёртого — лучший ход каждого
не складывается в лучший ход армии; у него же строй ходит линией шаг в шаг. Командир раздаёт клетки, считая опасность
сейчас и на следующий тик (его мили в MELEE_KEEP_RANGE шагнёт и ударит): мили — вплотную к его вооружённым, стрелки —
где есть цель в RANGED_RANGE и меньше входящего, лекари — в HEAL_RANGE от раненого и вне огня; клетка занимается один
раз, крипы обслуживаются от самого стеснённого; атаки идут своим проходом. В цепочке цели приказ стоит раньше всего
боевого (`whyTag = "order"`). Включался только в бою с сомкнутым блобом; v139 добавил «не против того, кто уходит» (в
kite командир держал армию в размене вместо флагов — 0:21 899 и 0:21 082 при исправном CPU) и «только когда рубка идёт»
(camp 13 311:23 559). Состояние на 08.09.2026: гейт 131/131, замыслы различаются (в одном матче PRESS, HOLD, YIELD и
FOCUS), симуляция v138 чинит то, что командир один портил (без неё гейт 128). Живые замеры: первая версия 2-4 и 1-5, с
распадом частей 1-5 и 2-4, с веером в модели 3-3 и 0-6, без кайта в наборе замыслов 1-9 против MetalicaX#10 — командир
вытеснял из цепочки правило v135, которое одно и работало; кайт добавлен пятым замыслом (гейт вернулся к 131/131).
Измерено 08.09.2026 после перезапуска клиента: 32 тестовые игры — MetalicaX#10 3-13, MetalicaX#11 6-10, вместе 9-23 (28
%) против 7-17 (29 %) у v135 — паритет: архитектура работает, CPU 19,9 мс при пределе 100, но кайта не превосходит.
Настройки, каждая замерена и отвергнута: вес живого тела (гейт 128 и 129), накопленный размен в оценке (129 дважды;
`SIM_EXCHANGE_DIV = 0.0` — как основа с делителем 4 и как поправка с делителем 16 роняет гейт до 129/131, m32 army
уничтожена на 407–419-м тике), надбавка его урону (2-6 и 2-6), выбор цели фокуса симуляцией (1-7 и 2-6; проведённая до
стрельбы — 129 и m33:kite 0:21 135; `USE_COMMANDER_FOCUS = false`), раскатка по политике (1-5 и 1-5), замысел SPREAD
(130 дважды), глубина 3 и 6 (по 129), назначение только достижимых за тик клеток (130). Серия из двадцати на полном поле
(матчи 617–636, v140 с включённым командиром): 13:7, рейтинг 1 221 → 1 222, то есть +1 — против 16:4 и +45 у v135; по
его ботам けろびー#12 2-1, Coldkimchi#1 1-1, MetalicaX#4 1-1, ricardo 2-0, побеждён даже けろびー#1, но MetalicaX#9 0-2 и #10
0-1 — все три разгрома за 200 тиков. На блобах командир не хуже (31 % против 29 %), а на полном поле теряет там, где
кайт выигрывал: 65 % против 80 %. Выключен, играл v135; включён снова в v141, когда раскатка по замыслу
(`USE_ROLLOUT_BY_INTENT = true`) сделала прогноз точнее — けろびー#1 4-4 (50 %) против 31 % у v140 на блобах и 29 % у кайта.
С тех пор судья прежний — серия.

**v142 — лекарь при бойце (оператор увидел в повторе: «лекари всегда очень далеко»; правило прохода `healer` в
`commandFight`, своего тумблера нет).** Замер по записи разгрома 6aa072a0 (14 наших смертей против 0 его): среднее
расстояние от лекаря до ближайшего своего бойца — 4,1 клетки у нас против 1,7 у него при дальности лечения 3, то есть
наш лекарь в среднем стоял вне дальности и не лечил вовсе. Причина в раздаче клеток: лекарь ранжировался как incNext*100
+ расстояние, опасность весила стократно, и он выбирал безопасную клетку подальше; а фолбэк, когда клетки в дальности не
нашлось, отправлял его в самую безопасную — прочь от боя. Здесь порядок обратный: близость главная (и не 3, а 2, как у
него), опасность — тай-брейк, фолбэк ведёт к бойцам. Дважды переписано позже: v183 — самая безопасная клетка из тех,
откуда достаёт (ближайший раненый — тот, кто в контакте, и лекарь шёл за ним в рубку); v186 — лекарь прикрыт телами
своих (его лекари сохраняют лечащие части 100 % боя, наши 8 % при одном и том же теле `h6m6`, где лечение стоит первым и
гибнет первым).

**v143 — мили не бросается под верную смерть (оператор: «выбрасываться могут либо под фокусфаер чтобы быстро убить 1
чужого крипа, либо когда у нас преимущество по силе в бою»).** Прежде замысел напора ставил мили вплотную к его
вооружённому всегда, а симуляция выбирала напор по мощи, которую сама же и считала; правило разрешало выброс ровно в
двух случаях — залп армии по слабейшему за тик перекрывает его хиты вместе с лечением, которое до него дотягивается, или
наша мощь не ниже его, — иначе напор исполняется как удержание. Вердикт пришёл в v214: переменная `meleeCommit` была
объявлена и никем не читалась, то есть обещанного не было вовсе, и тумблер `USE_MELEE_COMMIT` не гейтил ничего; удалён.
Оба слагаемых сегодня есть в лучшем виде — перевес местный (`spotEdgeAt`, поле удара в клетке врага, а не мощь армии),
добиваемость — `killTicks`/`killableNow`, которыми пользуется фокус. Из v143 же счётчик `cmdTicks` — сколько тиков
командир правил армией (диагностика).

**v147 — лекарь выше строя в бою (`USE_HEALER_OVER_SLOT = true`).** Замер по записи 6aa078c0: у обеих сторон по три
лекаря, но у него в дальности лечения стоят все три каждый тик, а у нас 1,8 из трёх, и лечение выходит 1584 против 9624
— вшестеро. Причина в порядке цепочки: слот стоял выше подопечного, а расстановка не знает, кого лечить, и уводила
лекаря в строй за пределы дальности; лекарь вне HEAL_RANGE не лечит вовсе, поэтому в бою подопечный обязан стоять выше
слота. Вне контакта порядок прежний.

**v150 — командир на любой бой и единый кулак (оператор: «командир должен оркестрировать всю игру, чтобы крипы были
единым организмом»; `USE_COMMANDER_EVERY_FIGHT = true`, `COMMAND_MIN_FOES = 6`, `USE_FIST = true`, `FIST_RADIUS = 4`).**
Прежде командир включался только против сомкнутого блоба, а в прочих боях армия шла по старым правилам — то самое
смешение стратегий; условие теперь одно — контакт с вооружённым врагом. Порог назван числом: при трёх его вооружённых у
нашей армии гейт даёт 132 из 135 (camp 2 970:21 279, scatter, kite) — раздача клеток берётся вести бой против того, кто
строем не дерётся; при шести, то есть при настоящем блобе, гейт держит все 135. На стенде власти это не прибавило
(cmd=12/4 как и было: там враг и так сомкнут), но снимает требование сомкнутости живьём, где пара «кулак + широкое окно»
и дала 8-8. Кулак (оператор: «единый кулак в бою, оптимальное распределение клеток, никаких толканий»): раздача была
покрипной — каждый брал свою лучшую клетку, и оператор увидел в повторе «крипы разбегаются, нет единого кулака». Якорь —
медиана боевых (среднее тянет один отставший крип), за FIST_RADIUS от него приказ не выходит. Радиус замерен по обе
стороны против MetalicaX#10: 3 даёт 2-6 (кулак теснее строя — его веер накрывает всех разом, а нашим стрелкам негде
держать дистанцию), 5 даёт 6-10 за шестнадцать (кулак шире строя — уже не кулак), 4 даёт 8-8 за шестнадцать.

**v151 — командир ведёт и подход (`USE_COMMANDER_APPROACH = false`, `COMMAND_APPROACH = 8`) — ОТВЕРГНУТО замером.** Пока
командир включался только в контакте, армия сходилась к бою по старым правилам и собиралась в кулак уже под огнём —
переход между двумя управлениями и есть смешение стратегий; правило отдавало ему власть, едва его вооружённый в
COMMAND_APPROACH. Замер: 0-8 против MetalicaX#10 там, где кулак в контакте даёт 8-8. Причина: раздача клеток умеет ровно
одно — расставить армию относительно его строя, — а на подходе это не задача: там надо выбрать, куда идти всей армией и
когда вступать, а командир вёл её вплотную к врагу фолбэком «ближе к нему». Подход остался за прежней логикой до
появления у командира своего замысла похода — им стали режимы v160 и марш ядра v163.

**v153 — приказ в двух шагах (упомянута только вскользь, в комментарии v170).** Двойка COMMAND_REACH принята в v153
замером 2-6 против MetalicaX#10 (по гейту единица давала 130/131 против 131/131 у двойки: цель в двух клетках ведёт
крипа туда, где он будет нужен) — но тогда у командира не было ни цепочек, ни притяжения к приказу, ни цены пути, и
приказ на шаг просто не давал ему развернуть строй. Отменена в v170.

**v160 — режимы командира: гонка и поход (оператор: «перевести все действия на него», «в случае затишья — раздавать
задания по захватам флагов небольшими группами»; `USE_COMMAND_RACE = true`, `USE_COMMAND_MARCH = true`, `enum CmdMode {
FIGHT, RACE, MARCH }`, `cmdDetach`, `MARCH_SAFE = 12`, `RACE_PARTY = 3`).** Весь расчёт командира стоял внутри `if
(blockOn)`, а blockOn требовал постуры ANNIHILATE, врагов в поле и отсутствия добивания — командир молчал везде, кроме
рубки: замер показал mode=FIGHT в 150 строках лога при cmdTicks=26. Теперь он считается всегда и сам называет режим:
раздача клеток против строя — один режим из трёх, а все формы, на которых расширение окна падало (camp, roost, scatter,
kite), просят другого — там враг сидит на флагах, разбегается или держит дистанцию, и выигрывает счёт. В гонке командир
раздаёт задания: каждому незанятому флагу — ближайшая горстка, по одному крипу на свободный флаг и по двое на тот, у
которого стоят его вооружённые (RACE_PARTY при его подвижной группе не меньше COMMAND_MIN_FOES); ядро (половина армии)
остаётся целым; поход — тот же вопрос, только врага рядом нет, и его отделяет MARCH_SAFE (кайтер держится в двух шагах
за границей контакта, и командир разбирал против него армию по одному — match29:kite давал 0 очков против 22 644, а с
выключенным походом кончается уничтожением его армии). Что поймал гейт по дороге: первая редакция игнорировала
captureAllowed — camp 7 911:17 364, scatter 15 273:24 303 против 22 810:19 091 и 24 268:17 098 без режима; одиночка
посылается, только когда перехватывать некому (match29:kite — наши уходили по одному, армия таяла до двух крипов при
0 : 22 469); ядро обязано остаться сильнее его армии по PARITY_FLOOR (без этого гейт 127); идущий бегун засчитывается в
группу (иначе гейт терял roost 7 597:24 268 и camp), а флаг, который уже берёт бегун, не дублируется (досылать бойца —
131 из 135 против 133); задание — зачисление в захватчики, а не клетка (первая редакция на kite набрала 0 очков);
задания снимаются вместе с режимом (иначе крип оставался захватчиком навсегда — kite 0 очков); добивание ведёт охота, а
не строй (match29:kite 0 : 22 644 против уничтожения его армии на 299-м без командира). Состав для гонки обещано считать
вместе с уже отпущенными — v215 нашёл, что `cmdDetach.clear()` стоял строкой выше чтения и фильтр был пуст всегда
(`USE_RACE_COUNTS_RELEASED`).

**v161 — огонь по приказу (оператор: «переведи выбор целей на командира»; `USE_COMMAND_FIRE = true`, карта `fireOf`).**
Прежде расстановку решал командир, а цели — отдельный проход со своим липким фокусом: два решения об одном размене. Цель
назначается поимённо одной политикой: сперва тот, кого армия добивает этим тиком (залп всех, кто его достаёт,
перекрывает хиты вместе с лечением, которое до него дотягивается) — на него идут все достающие; если добить некого,
огонь сходится на прежней липкой цели, а кто её не достаёт, бьёт ближайшего вооружённого. Назначения считаются на всю
силу, включая захватчиков с оружием; у стрелка приказ первым, если цель в RANGED_RANGE и её не добивает уже
забронированный огонь (отказ от перебоя, v140); у мили — после цели, которую он добивает этим ударом (добить дороже, чем
исполнить приказ). Отличие от отвергнутой цели фокуса v138 (`USE_COMMANDER_FOCUS = false`: 1-7 и 2-6 против 3-13,
проведённая до стрельбы — 129/131 и m33:kite 0:21 135): та подменяла липкий фокус, эта влияет мягко, через порядок
focusOrder.

**v162 — лечение по приказу и постура от командира (оператор: «перевести всё на командира»; `USE_COMMAND_HEAL = true`,
карта `healOf`; `USE_COMMAND_POSTURE = true`).** Лекарь выбирал пациента сам — по дефициту хитов плюс ожидаемый входящий
— и не знал того, что командир уже посчитал для огня: кого враг добивает этим тиком. Пациент назначается поимённо:
сперва тот, кого убивают сейчас и кого лечение ещё спасает (иначе это лечение в труп), на него ровно столько лекарей,
сколько нужно перекрыть входящий; остальные по наибольшей нужде; порядок лекарей от дальнего к ближнему, чтобы ближний
добирал остаток. Постура считалась до командира и задавала ему режим — распорядителем была она; теперь режим
определяется по существу боя (контакт, огонь, его группа, не добивание, мы не бежим), а постуру ставит режим: решил
драться — армия уничтожает. v183 расширил приказ лекаря на всю лечебную дальность (прежде назначенный пациент брался
только вплотную).

**v163 — ядро строем вне боя (`USE_COMMAND_CORE_MARCH = true`, `commandMarch`).** В гонке и походе командир раздавал
только задания на захват, а ядро шло врозь по прежним веткам и приходило к бою растянутым; теперь он ведёт его как одно
тело: шаг в сторону цели, клетки по одной на крипа, отставший подтягивается к якорю, ни одна клетка не выходит за
FIST_RADIUS. Только пока враг далеко (MARCH_SAFE): рядом с ним решают тактические ветки — экран, добыча, перехват, — и
строй, ведущий ядро на флаг мимо них, ронял screen и scatter (гейт 131 из 135). Направление задаёт путь, а не прямая на
цель: жадный шаг упирался в стену, и сценарий screen шёл в режиме марша все 185 строк лога, проигрывая 10 782:14 471.
Комментарий обещал «его шаг по полю потока и есть направление», реализован был путь по матрице толпы — это нашёл разбор
затора марша в v232 (`USE_MARCH_FLOW_DIRECTION`, см. выше).

**v164 — цель похода и отряд под командиром (`USE_COMMAND_GOAL = true`, `USE_COMMAND_GOAL_OWN = false`, `GOAL_GUARD_COST
= 6`; `USE_COMMAND_OWNS_DETACH = true`).** Командир вёл ядро к цели, выбранной не им (objectiveFlagId считался раньше
отдельной логикой); теперь выбирает сам — флаг, который брать можно (мимо гейта захвата ходить незачем: флаг дебаффает
владельца), ближайший к армии, со штрафом за его вооружённых рядом. Своя формула написана и отвергнута замером: по
близости 131 из 135 (camp дважды, screen, brawl+heals), по ценности на шаг 132, с квадратичным штрафом расстояния снова
131 — поэтому командир вызывает `chooseFlagObjective` (ценность, путь, пачка у флага, отход) и решает сам. Выпуск
захватчиков был вторым распорядителем; механика остаётся (она знает сухую охоту, гонку и охрану стрелков, чего
командирская раздача не покрывает: полная замена дала 133 из 135 — roost 7 615:24 325, camp 22 304:23 966), но включает
её режим командира.

**v165 — расстановка под командиром, стрелок за мили и отход по прогнозу (оператор: продолжать переносить логику в
командира; `USE_COMMAND_OWNS_SLOTS = false`, `USE_COMMAND_RETREAT = false`, `COMMAND_RETREAT_SCORE = 0.0`).** Слоты
строя и командирские клетки отвечают на один вопрос, и это выглядело двоевластием; замер сказал иначе: с молчащей
расстановкой match32:army кончается уничтожением нашей армии на 370-м тике (3 738:2 708), с работающей армия доживает до
конца семью крипами. Три попытки закрыть разрыв внутри командира не помогли: добор клетки каждому без приказа (134; с
перехватом лекарей и раздетых — 133), штраф стрелку за место впереди мили (те же 134), режим боя при сближении армий
(133, падал ещё и brawl+heals). Расстановка — не второй распорядитель, а запасной исполнитель: приказ стоит выше слота,
слот достаётся только тому, кому командир клетки не дал. Из этих проб остался проход `catchall` — вооружённый не
остаётся без места (лекарей и раздетых он не трогает: их перехват давал 133 из 135). «Стрелок за мили» — правило рядов
расстановки, перенесённое в раздачу клеток, — лежало выключенным с выводом «предмета нет», и вывод оказался неверным
(v183): замер разгрома 3d9532 в общей рамке обеих армий — наши мили на 0,6 клетки позади своего центра, стрелки на 0,9
впереди; у него мили +0,5, стрелки −0,4; строй был вывернут наизнанку весь бой. Комментарий говорит «включено вместе с
USE_RANGED_BEHIND_HARD», но такой константы в файле уже нет — сегодня это запрет в раздаче (клетка ближе к его строю,
чем медианная линия наших мили, стрелку не предлагается; RANGED_BEHIND_COST = 40 рядом с incNext × 100 не решал ничего)
и выбор «уцелеть» при несовместимости с «стрелять» (выстрел стоит 60, стрелок — 1 200). Отход по прогнозу: если лучший
из замыслов кончается перевесом врага по уцелевшей мощи, командир объявляет отход сам. НЕ ВКЛЮЧЕНО, и причина важнее
правила: оценка замысла ни разу не уходит в минус — при пороге −2 не сработало ни разу, при откалиброванном −300 (оценка
живой армии держится около 1 200–1 400) — ни разу, при пороге ноль — ни разу на трёх боевых картах; прогноз всегда
обещает перевес и основанием для отхода служить не может, пока не понято, почему он систематически оптимистичен. Отсюда
прибор v166 и, позже, отход по измеренной мощи (v185).

**v166 — ошибка прогноза: прибор, а не правило, и пробы модели, судимые им (`USE_SIM_ERROR = true`, `USE_ORDER_AUDIT =
true`, `SIM_TICKS = 1`, `SIM_STRIPPED_OUT = true`, `SIM_HEAL_IN_RANGE = true`, `SIM_BLOCKED = true`,
`SIM_FOE_RANGED_HOLDS = false`, `SIM_FOE_NEAREST = false`).** Командир обещает разность мощи через SIM_TICKS; прибор
запоминает обещание и на SIM_TICKS-м тике сверяет с фактом, считая среднюю ошибку и число случаев, когда обещали
прибыль, а вышел убыток (в логе `sim t=… err=… wrongSign=…`); факт меряется той же формулой, что прогноз — первая
редакция сравнивала оценку симуляции с ланчестеровской мощью, то есть разные величины. Глубина прогноза прежде мерилась
только гейтом (3 и 6 давали 129/131, 4 — 131/131); прибор показал, что ошибка растёт с глубиной почти линейно — 854 при
единице, 1 304 при двойке, 3 855 при четвёрке, — а неверный знак 33 %, 38 % и 44 %; на четвёрке ошибка втрое больше
самой оценки (1 200–1 400), единица точнее и вчетверо дешевле по CPU, гейт при ней 135/135. Пробы модели: раздетый
уходит и в прогоне (1 063 против 1 071, знаки те же 24 % — модель перестала расходиться с приказом); лекарь лечит
достижимого (знаки 24 % против 27 %, средняя ошибка 1 071 против 1 016 — взято ради знака и физической верности); клетка
занята — крипы не проходят сквозь друг друга (1 016 против 1 302 без запрета, точнее на 22 %, знаки 27 % против 26 %; по
картам разброс есть — match28 971 против 854, match35 1 165 против 1 971, — потому и мерилось по нескольким); его
стрелок стоит, а не пятится — ОТВЕРГНУТО (882 против 854 на match28 при том же 1 971 на match35); его мили идут к
ближайшему вообще, а не к мягкому — ОТВЕРГНУТО (1 035 против 854 и 2 392 против 1 971).

**v167 — исполнение приказа (`USE_ORDER_AUDIT`, `orderPrev`, `ORDER_PRIORITY = 4`; `USE_ORDER_DIRECT = false`,
`USE_ORDER_FREE_CELLS = true`, `ALLY_CELL_COST = 25.0`).** Прогноз считает, что крип встанет туда, куда назначено, а
между приказом и клеткой стоят трафик, свопы и фатиг; прибор считает долю тех, кто на следующем тике оказался ровно на
своей клетке — и показал 7 % (10 из 144), приближаются 32 %: назначенная клетка была лишь одним слагаемым в оценке шага,
и опасность её перевешивала, прогноз опирался на фикцию. Приказ как прямой шаг — ОТВЕРГНУТО: гейт 133 из 135 (camp
17 686:20 345, brawl+heals с уничтожением армии), доля дошедших упала до 3 % — крип, которому приказано шагнуть строго в
клетку, теряет право обойти опасность и застревает; приказ остаётся целью. Приказ в свободную клетку: клетка под своим
больше не назначается (кроме собственной, что значит «стой») — такой приказ неисполним, пока сосед не ушёл. Приказ
командира получил приоритет в трафике выше прочих: он считает всю армию сразу.

**v168 — план вглубь и притяжение к приказу (оператор: «если крипу необходимо уйти на клетку, на которой сейчас стоит
крип, зачем этому крипу необходимо сдвинуться на другую, и если там стоит крип, то сдвинуть и его, и так далее»;
`USE_ORDER_CHAINS = true`, `CHAIN_DEPTH = 4`; `USE_ORDER_PULL = true`, `ORDER_PULL = 2.0`).** Раздача рекурсивна:
занятая клетка не отвергается и не просто дорожает — её жилец получает приказ уйти, и цепочка идёт до CHAIN_DEPTH
звеньев; обмен местами — вырожденная цепочка длины два, разрешён отдельно, и им делается ротация состава. Притяжение:
расстояние до назначенной клетки весило столько же, сколько влияние, угроза мили и разделение, и они его перевешивали
(исполнение 7 %); теперь оно множится, но обход опасности остаётся. Множитель замерен: при 4 приборы лучше всего
(исполнение 9 % против 7,6 %, приближение 36 % против 33 %, ошибка прогноза 832 против 956), но гейт падает до 133 из
135 (camp 19 521:20 793, brawl+heals с потерей армии) — слишком сильное притяжение ведёт крипа в клетку сквозь огонь;
при 2 гейт держит 135, приближение 34 %.

**v169 — опасность пути (оператор; `USE_ORDER_PATH_DANGER = true`, `PATH_DANGER_W = 1.0`, `PATH_BLOCKED_COST =
1000.0`).** Клетка в двух шагах достигается через промежуточную, а командир оценивал только конечную; если промежуточная
под огнём, движение честно отказывалось туда идти — и приказ терялся. Стоимость пути — самая безопасная из промежуточных
клеток. Вес замерен: тройка роняет гейт (134 из 135) и исполнения не добавляет (те же 14 из 141), единица держит 135. С
v170 (приказ на шаг) промежуточных клеток нет: в коде `pathDanger` возвращает ноль для клетки в одном шаге, а
COMMAND_REACH = 1.

**v170 — приказ ровно на шаг и очередь движения от командира (оператор: «командир должен согласовать все движения»;
`COMMAND_REACH = 1`; `USE_COMMAND_TRAFFIC = true`, `ORDER_PRIORITY_MELEE = 7`, `ORDER_PRIORITY_RANGED = 6`,
`ORDER_PRIORITY_HEAL = 5`).** Прибор показал цену двойки: 84 приказа из 141 были на расстоянии два, то есть неисполнимы
за тик по построению, а исполнялось всего 10 %. Теперь, когда есть цепочки, притяжение и цена пути, приказ строго на шаг
даёт исполнение 34 % против 10 % на match28 (48 из 143) и 29 % против 7 % на match35, ошибку прогноза 1 219 против
1 390, ноль недостижимых приказов и гейт 135/135. Очередь: разрешение конфликтов — поиск в глубину с цепочками и свопами
по приоритету — уже было устроено правильно, но приоритет задавали разрозненные места (раненый, боец, захватчик) и
замысел в нём не участвовал; теперь крип, исполняющий приказ, идёт первым, а среди приказов вперёд пропускается мили,
выходящий в контакт, затем стрелок с целью, затем лекарь к подопечному, и лишь потом все прочие. Прибор разложил и
потери приказа: «стой», а крип ушёл; не двинулся вовсе; двинулся в другую клетку; не мог от усталости (`lost=…`), и
отдельно — сколько приказов вообще недостижимо за тик (`far=`).

**v171 — приказ выше слота и остановки (`USE_ORDER_OVER_SLOT = true`).** Приказ задавал цель, но в выборе шага не
участвовал — его перехватывали слот строя и hold, и он работал только в последней ветке. Разбор потерь: из 143 приказов
50 кончались уходом в другую клетку, 36 — стоянием. Первая редакция — только в бою: в гонке очков приказ марша перебивал
удержание, и camp падал 4 155:16 209; во все режимы приказ вышел в v172.

**v172 — приказ — закон, исполнимость и коллизии (оператор: «все крипы должны двигаться ТОЛЬКО по приказу командира…
нельзя не слушаться приказов командира», «командир должен быть уверен, что каждый крип на следующем шагу сможет
выполнить приказ», «не должно быть такого, что по приказам командира в одну клетку собрались двое»; `USE_ORDER_IS_LAW =
true`, `USE_ORDER_EVERY_MODE = true`, `USE_ORDER_FEASIBLE = true`, `TrafficManager.SWAP_RESPECTS_INTENT = true`, счётчик
`clash`).** Назначенная клетка исполняется буквально, без пересчёта весов движения; прежняя попытка (v167) провалилась —
гейт 133, исполнение 3 % — потому, что командир раздавал клетки, не считая того, что считает крип; теперь считает
(`rankStep` в `commandFight`: огонь на клетке, свой огонь с неё, теснота, цена пути), и цена ошибки лежит на нём, а не
на непослушании. В гонке и походе приказ тоже закон. Исполнимость: уставшему крипу можно приказать только стоять — иначе
приказ ложь, которую потом считает прогноз. Своп в трафике не разменивает крипа с приказом в чужую клетку: именно так
терялись приказы, и прибор считал это «ушёл в другую клетку» (неприкосновенны только те, у кого приказ командира: запрет
для всякого желания сломал развязку). Коллизии внутри одной раздачи исключены множеством taken, но приказы приходят из
разных мест — бой, гонка, марш ядра, — и там пересечение считается.

**v173 — хранитель под командиром (оператор: «уйти с флага крип должен только если командир решит собрать отряд, или
если крип может попасть в опасность»; `USE_ORDER_OVER_KEEPER = true`, `commandArmy`).** Прежде хранитель был невидим для
командира (mobileArmy исключает keeperIds) и приказа не видел — он был вне командира по построению; теперь командир
видит всё поле, включая хранителей, и снимает с флага решением, а не случайно, держат же флаг они по-прежнему сами, пока
приказа нет. С той же версии марш даёт те же гарантии, что бой (клетка не занята своим, крип способен шагнуть, одна
клетка — одному: прежде колонна раздавала клетки своим кодом без этих проверок), захватчик исключён из аудита (его
приказ — флаг, а не клетка), а приборы разложили остаток неисполнения: сколько приказов дошло до ветки исполнения
(`branch`), сколько сломало бегство (`fled` — оно стоит выше приказа намеренно) и сколько раз трафик выдал крипу с
приказом не ту клетку (`TrafficManager.orderedChanged`).

**v174 — удержание флага и отход приказом (оператор: «не должно быть ничего, что идёт мимо него»;
`USE_COMMAND_HOLDS_FLAGS = true`, `USE_COMMAND_RETREATS = true`).** Обе ветки шли мимо командира и стояли выше его
приказа. Теперь крип на нашем флаге стоит по приказу командира (снять его может только он, решив собрать отряд, или
опасность, которая приходит приказом), и командир сам уводит того, кому грозит гибель — потерявшего за тик больше
половины остатка (в коде — и при хитах ниже трети максимума) или стоящего под огнём без лечения рядом (проход `retreat`
перед всеми прочими назначениями).

**v175 — жилец, которому приказано стоять, остаётся препятствием; клетку мог занять враг.** Прежде всякий, кто уже
получил приказ, считался уходящим — а приказ «стой» (хранитель флага, крип на своём месте) никуда его не уводит, и
назначенная поверх него клетка оказывалась неисполнимой; это и был весь оставшийся процент неисполнения: 17 случаев на
5 294 приказа, все вида «крип остался на месте». Клетку приказа мог занять и враг — он ходит одновременно с нами, и его
шаг делает приказ неисполнимым задним числом; это неустранимо в принципе и считается отдельно (`lostEnemy`).

**v176 — ни одной клетки двоим, спасение старше любого приказа (оператор: «не должно быть такого, что по приказам
командира в одну клетку собрались двое»; `USE_ORDER_NO_DUPES = true`).** Раздача держит своё множество занятых, но
источников приказа несколько — бой, гонка, марш, хранители, отход, — и на стыке коллизия случалась: одна на 431 приказ
(режим боя). Здесь она снимается безусловно: клетка остаётся за первым, второй теряет приказ и идёт по общим правилам.
Спасение: если самая безопасная клетка занята крипом, которому велено стоять, приказ стоять снимается и жилец уводится
цепочкой — беречь расстановку ценой крипа армия из четырнадцати не может; снимается только у выбранного жильца (первая
редакция снимала приказы у всех подряд в переборе кандидатов, и прибор поймал это коллизией clash=1), при неудаче
цепочки снятый приказ возвращается, и только если его клетку никто не занял (слепое восстановление отдавало клетку двоим
— clash в режиме боя, t=808); хранитель не занимает клетку, уже отданную приказом.

**v177 — стрелок держится от его мили, вырвавшийся вперёд возвращается (оператор: «в момент начала боя у нас всегда был
1 крип где-то впереди, и его очень быстро убивали»; `USE_PULL_STRAGGLERS = true`).** Замер по записи разгрома: наши
стрелки стояли вплотную к его вооружённому мили 33 крипо-тика и в двух клетках ещё 41 из 242 стрелковых; клетка ближе
MELEE_KEEP_RANGE (= 2) к его мили стрелку не назначается — правило без своего тумблера (KDoc стоит без константы);
сегодня это требование всех замыслов стрелка в раздаче — быть вне досягаемости его мили и доставать цель (см. v183).
Кулак ограничивал кандидатные клетки, но крипа, уже стоящего вне кулака, не возвращал — замер даёт отрыв до 10 клеток
при среднем 1,8, и это тот самый одиночка, которого быстро убивают; такому назначается шаг к якорю прежде всех прочих
приказов (проход `straggler`).

**v179 — строй до боя (оператор: «мы стояли на флаге 30-40 тиков, и всё равно, когда враг подошёл, мы были не готовы —
отряд растянут, впереди стояли рэнж-крипы, а у него компактный отряд с милишниками спереди»; `USE_COMMAND_BRACE = true`,
`BRACE_WIDTH = 3`, `commandBrace`).** Пока враг идёт, а контакта нет, командир строит фронт вокруг своего якоря: ось —
направление на его центр, мили на ближней к нему линии, стрелки за ними, лекари в тылу; это не отвергнутый
USE_COMMANDER_APPROACH — там армия шла вплотную к врагу раздачей клеток по его строю, здесь никто не сближается. Условие
«враг в десяти клетках» без признака сближения — ОТВЕРГНУТО: изготовка вставала поперёк гонки за флагами, армия
строилась вместо захвата, и гейт рухнул до 122 из 135 (roost трижды); условие именно «он идёт на нас». Дальнейшая судьба
записана в доках при v183 (изготовка не выполнялась ни одного тика: armiesClosing набирался только в контакте) и v215
(включена замером).

**v180 — захват при отставании (разбор серии; `USE_CAPTURE_WHEN_LOSING = true`).** Пять поражений из семи — не бой, а
гонка очков, где обе армии целы, а мы держим три флага против его четырёх и набираем 10-12 очков в тик против его 13-15.
Вето контакта не давало взять четвёртый почти весь матч, а снималось лишь за LAST_CALL_TICKS до конца — после того, как
отрыв уже сделан; теперь оно снимается, как только мы отстаём и по счёту, и по скорости. Паритет мощи остаётся: доктрина
не в том, чтобы не брать флаги, а в том, чтобы не брать их ценой армии. Серия v180: 12-8 с рейтингом +16,
гонок-поражений три вместо пяти, но разгромов пять вместо двух — армия гибла к 200-300 тику. Поэтому v181 снимает вето
только при перевесе, а не при простом паритете (`USE_CAPTURE_NEEDS_EDGE = true`, `CAPTURE_EDGE = 1.1`).

**После эпохи: v212 — погоня не зависит от того, правит ли командир.** Погоня отрядом за остовами (v211) в первой
редакции стояла под `commanderNow`, и первый же живой матч это закрыл: `cmd=8/1900` — командир правил восемь тиков из
тысячи девятисот, погоня не выдалась ни разу (`chase=0/0` при двух остовах). Доля командира гуляет по матчам от 8 тиков
до двух третей, поэтому назначение (`assignChase`) стоит выше него: приказ один, а исполняют его оба пути движения —
командирская раздача, когда он правит, и обычная цепочка целей (ветка `chase`, сразу под приказом командира и выше кайта
и строя — обе увели бы преследователя обратно в кулак), когда молчит.
