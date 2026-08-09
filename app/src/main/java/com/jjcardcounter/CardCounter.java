package com.jjcardcounter;

import java.util.Arrays;

/**
 * 记牌器核心：一副牌 54 张，按点数统计剩余。
 * type 索引约定：
 *   0=小王  1=大王
 *   2=3 3=4 4=5 5=6 6=7 7=8 8=9 9=10 10=J 11=Q 12=K 13=A 14=2
 */
public class CardCounter {
    public static final String[] TYPE_NAMES = {
            "小王", "大王", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2"
    };

    private final int[] deck = new int[15];      // 每种牌总数
    private final int[] hand = new int[15];      // 自己手里当前每种牌数量
    private final int[] played = new int[15];    // 累计已经打出（含三家）的每种牌数量
    private final int[] lastDesktop = new int[15]; // 上一帧桌面可见牌（用于检测新出的牌）

    public CardCounter() {
        reset();
    }

    public void reset() {
        deck[0] = 1;
        deck[1] = 1;
        for (int i = 2; i < 15; i++) deck[i] = 4;
        Arrays.fill(hand, 0);
        Arrays.fill(played, 0);
        Arrays.fill(lastDesktop, 0);
    }

    /**
     * 每帧调用：传入识别到的手牌计数和桌面可见牌计数（均为长度15的数组）。
     * 桌面新出现的牌计入 played（只增不减，因此即使出牌被覆盖也不会丢失）。
     */
    public void update(int[] handCount, int[] desktopCount) {
        if (handCount != null) System.arraycopy(handCount, 0, hand, 0, 15);
        if (desktopCount != null) {
            for (int i = 0; i < 15; i++) {
                int delta = desktopCount[i] - lastDesktop[i];
                if (delta > 0) played[i] += delta;
            }
            System.arraycopy(desktopCount, 0, lastDesktop, 0, 15);
        }
    }

    /** 剩余 = 总数 - 自己手牌 - 已打出 */
    public int remaining(int type) {
        int r = deck[type] - hand[type] - played[type];
        return r < 0 ? 0 : r;
    }

    public int[] getRemaining() {
        int[] r = new int[15];
        for (int i = 0; i < 15; i++) r[i] = remaining(i);
        return r;
    }
}
