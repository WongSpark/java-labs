package com.lab.water.classify;

import lombok.Builder;
import lombok.Data;

/**
 * 分类与层级划分的阈值参数配置。
 * 可根据实际数据特征调整各阈值。
 */
@Data
@Builder
public class ClassificationConfig {

    // ========== 类型判定阈值 ==========

    /** 大洋最小面积（km²），默认 1,000,000 km²（约里海级别） */
    @Builder.Default
    private double oceanMinAreaSqKm = 1_000_000;

    /** 河流最小狭长度（长轴 / 短轴 ≥ 此值判断为河流） */
    @Builder.Default
    private double riverMinSlenderness = 5.0;

    // ========== 层级划分阈值（按面积 km²） ==========

    /** Level-1: 大洋级（面积 ≥ 1,000,000 km²） */
    @Builder.Default
    private double level1MinAreaSqKm = 1_000_000;

    /** Level-2: 大型水体（面积 ≥ 10,000 km²） */
    @Builder.Default
    private double level2MinAreaSqKm = 10_000;

    /** Level-3: 中型水体（面积 ≥ 100 km²） */
    @Builder.Default
    private double level3MinAreaSqKm = 100;

    /** Level-4: 小型水体（面积 < 100 km²） */
    @Builder.Default
    private double level4MaxAreaSqKm = 100;

    // ========== 辅助方法 ==========

    /**
     * 根据面积获取层级编号
     */
    public int resolveLevel(double areaSqKm) {
        if (areaSqKm >= level1MinAreaSqKm) return 1;
        if (areaSqKm >= level2MinAreaSqKm) return 2;
        if (areaSqKm >= level3MinAreaSqKm) return 3;
        return 4;
    }

    /**
     * 获取层级标签
     */
    public static String levelLabel(int level) {
        return switch (level) {
            case 1 -> "Level-1-Ocean";
            case 2 -> "Level-2-Large";
            case 3 -> "Level-3-Medium";
            case 4 -> "Level-4-Small";
            default -> "Level-Unknown";
        };
    }

    /** 默认配置 */
    public static ClassificationConfig defaults() {
        return ClassificationConfig.builder().build();
    }
}
