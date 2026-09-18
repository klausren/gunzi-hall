"""服务端变异验证：把扣王/底牌公开相关的实现逐条改坏，确认测试真的会红。

【为什么每条变异都要 clean】
2026-09-17 实测踩过一个大坑：上一轮变异验证"恢复"源码时用的是保留时间戳的拷贝，
结果**源文件 mtime 早于 .class**，Maven 的增量编译判定"无需重编"，
变异版字节码一直留在 target/classes 里 —— 之后的"全绿"全是假绿，
直到反编译（javap -c）才看见 bottomVisible() 末尾还是 `iconst_0 / ireturn`（= return false）。
所以本脚本每次都用 `clean test`，宁可慢十几秒也不允许增量编译猜。

用法：python tools/_mut_check_server.py
"""
import os
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SERVER = REPO / 'server'
MVN = r'D:\ENV23\apache-maven-3.9.9\bin\mvn.cmd'

DOMAIN = 'game-domain/src/main/java/com/gunzihall/domain'
ROOM = 'game-room/src/main/java/com/gunzihall/room'

# (编号, 说明, 相对路径, 原文, 改坏后, 期望出现在失败清单里的关键字)
MUTATIONS = [
    (
        'S1', '捡牌排序反过来 → 捡走最大的那张（手册要求捡最小）',
        f'{DOMAIN}/room/BottomPickRule.java',
        'candidates.sort(new CardComparator(trump)); // 升序 = 牌力小的在前',
        'candidates.sort(new CardComparator(trump).reversed()); // MUT',
        ['pickJoker_swapsOwnJokerForSmallestNonPointCard'],
    ),
    (
        'S2', 'bottomVisible() 恒为 false → 扣王后底牌对谁都不公开',
        f'{ROOM}/RoomActor.java',
        '        if (room.phase() == GamePhase.BURYING && !room.isBottomTaken()) {\n'
        '            return true;\n'
        '        }\n'
        '        return room.isBottomRevealed();',
        '        if (room.phase() == GamePhase.BURYING && !room.isBottomTaken()) {\n'
        '            return true;\n'
        '        }\n'
        '        return false; // MUT',
        ['bankerBuriesJoker_bottomBecomesPublic',
         'pickJoker_swapsOwnJokerForSmallestNonPointCard',
         'anyPick_keepsBottomPublicAfterWindowCloses',
         'windowStaysSecretUntilSomebodyActuallyPicks'],
    ),
    (
        'S3', '不看别人手里有没有王，一律开扣王窗口',
        f'{DOMAIN}/action/BuryBottomCommand.java',
        'if (!dryPot && !hasPoints && othersHoldJoker(room, bankerSeat)) {',
        'if (!dryPot && !hasPoints) { // MUT',
        ['buryWithoutPointsButNobodyElseHasJoker_skipsWindowEntirely'],
    ),
    (
        'S4', '庄家扣王也不公开底牌（退回 2.3.3 之前的行为）',
        f'{DOMAIN}/action/BuryBottomCommand.java',
        'room.setBottomRevealed(hasJoker); // 2.3.3：扣王时底牌必须亮给所有人看',
        'room.setBottomRevealed(false); // MUT',
        ['bankerBuriesJoker_bottomBecomesPublic'],
    ),
    (
        'S5', '去掉"扣几张不能超过底牌可捡数"的上限校验',
        f'{DOMAIN}/action/PickBottomJokerCommand.java',
        '        if (jokers.size() > pickable) {',
        '        if (false) { // MUT',
        ['pickJoker_beyondPickableCount_rejected'],
    ),
    (
        'S6', '超时托管把"扣王窗口"当成"扣底"发（会把房间判成 stuck）',
        f'{ROOM}/RoomActor.java',
        '                    if (room.buryPickSeat() == waiter) {\n'
        '                        doPickJoker(waiter);\n'
        '                    } else {\n'
        '                        doBury(waiter);\n'
        '                    }',
        '                    doBury(waiter); // MUT',
        ['TimeoutTrustTest'],
    ),
    # ---- 以下三条针对"窗口期间不摊牌"（Tracy 拍板口径）----
    (
        'S7', '开扣王窗口时顺手把底牌摊开（退回"窗口期间就摊牌"的旧行为）',
        f'{DOMAIN}/action/BuryBottomCommand.java',
        '            room.openBuryPickWindow(List.of(bankerSeat.next(), bankerSeat.next().next(),',
        '            room.setBottomRevealed(true); // MUT\n'
        '            room.openBuryPickWindow(List.of(bankerSeat.next(), bankerSeat.next().next(),',
        ['buryWithoutPointsAndOthersHoldJoker_opensWindowForNextSeat',
         'windowStaysSecretUntilSomebodyActuallyPicks',
         'buryPickWindowOpen_newBottomStaysSecretUntilSomebodyPicks'],
    ),
    (
        'S8', '他人真扣了王却不公开底牌（2.3.5「扣王时底牌必须公开」落不了地）',
        f'{DOMAIN}/action/PickBottomJokerCommand.java',
        '        room.setBottomRevealed(true);',
        '        room.setBottomRevealed(false); // MUT',
        # 这里**不**期望 anyPick_keepsBottomPublicAfterWindowCloses 变红：
        # 那条被 GameRoom.refreshBottomReveal()（收口时重算"底牌含王 → 公开"）兜住了，
        # 单点坏打不穿 —— 这是真实的容错，不是测试漏洞；该断言由 S10 反向单独锁住。
        ['pickJoker_swapsOwnJokerForSmallestNonPointCard',
         'windowStaysSecretUntilSomebodyActuallyPicks'],
    ),
    (
        'S9', '收口时不重算公开标志（没人押过的底牌收不回去，恒 true）',
        f'{DOMAIN}/room/GameRoom.java',
        '        this.bottomRevealed = bottomCards.stream().anyMatch(Card::isJoker);',
        '        this.bottomRevealed = true; // MUT',
        ['allPass_closesWindowAndHidesBottomAgain'],
    ),
    (
        # S9 与 S10 是同一处的**两个方向**：公开标志被写死成 true（收不回机密）
        # 或被写死成 false（押过注的记录被抹掉）。S8 单点坏打不穿
        # anyPick_keepsBottomPublicAfterWindowCloses，正是靠 S10 这条反向锁住它。
        'S10', '收口时把公开标志一律清成 false（连"有人押过注"的记录也抹掉）',
        f'{DOMAIN}/room/GameRoom.java',
        '        this.bottomRevealed = bottomCards.stream().anyMatch(Card::isJoker);',
        '        this.bottomRevealed = false; // MUT',
        ['anyPick_keepsBottomPublicAfterWindowCloses'],
    ),
]

TEST_CLASSES = 'PickBottomJokerCommandTest,BottomRevealTest,TimeoutTrustTest'


def mvn_test(extra_env=None):
    env = dict(os.environ)
    if extra_env:
        env.update(extra_env)
    args = [MVN, '-o', '-pl', 'game-domain,game-room', '-am', 'clean', 'test',
            f'-Dtest={TEST_CLASSES}', '-Dsurefire.failIfNoSpecifiedTests=false']
    p = subprocess.run(args, capture_output=True, text=True, encoding='utf-8',
                       errors='replace', cwd=str(SERVER), env=env)
    return p.stdout + p.stderr


def failures(out):
    return [line.strip() for line in out.splitlines()
            if re.match(r'^\[ERROR\]\s+\w+Test[.#]', line.strip())
            or re.match(r'^\[ERROR\]\s+\w+Test\.\w+', line.strip())]


def main():
    bad = 0
    # 可选：只跑指定编号（`python _mut_check_server.py S8 S10`）——
    # 改完锚点/期望后快速复验，不必等全表跑完 10 分钟。
    only = {a.upper() for a in sys.argv[1:]}
    for mid, desc, rel, old, new, expects in MUTATIONS:
        if only and mid not in only:
            continue
        path = SERVER / rel
        original = path.read_text(encoding='utf-8')
        if old not in original:
            print(f'  [SKIP] {mid} 锚点失配，可能实现已变：{old.splitlines()[0][:60]}')
            bad += 1
            continue
        try:
            path.write_text(original.replace(old, new, 1), encoding='utf-8')
            out = mvn_test()
            reds = failures(out)
            hit = [k for k in expects if any(k in r for r in reds)]
            ok = len(hit) == len(expects)
            print(f'  [{"RED" if ok else "GREEN!"}] {mid} {desc}')
            print(f'         期望命中 {len(expects)} 条 / 实际命中 {len(hit)} 条，'
                  f'共红 {len(reds)} 条')
            for r in reds[:4]:
                print(f'         {r}')
            if not ok:
                print(f'         缺：{[k for k in expects if k not in hit]}')
                bad += 1
        finally:
            path.write_text(original, encoding='utf-8')   # 先恢复源码
            # 再把变异产物的 class 删掉，杜绝"源码恢复了但字节码还是坏的"
            cls_dir = SERVER / rel.split('/src/main/java/')[0] / 'target/classes'
            for cls in cls_dir.rglob('*.class'):
                if cls.stem == path.stem:
                    cls.unlink()

    print('=' * 49)
    print('最后跑一次干净全量，确认源码恢复后是绿的 ...')
    out = mvn_test()
    ok = 'BUILD SUCCESS' in out and '[ERROR]' not in out
    print('  恢复后全量：' + ('BUILD SUCCESS' if ok else '仍有失败！'))
    if not ok:
        bad += 1
        for line in out.splitlines():
            if '[ERROR]' in line:
                print('  ' + line.strip())

    print('=' * 49)
    print('全部变异都成功变红，且恢复后全绿' if bad == 0
          else f'{bad} 处有问题（测试有漏洞 或 源码没恢复干净）')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
