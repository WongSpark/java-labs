package com.lab.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条 GRIB2 记录的元数据摘要，对应统计表的一行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Grib2RecordInfo {

    /** 记录序号（按文件中出现顺序） */
    private int recordIndex;

    /** 变量名称，如 Temperature, U-component_of_wind */
    private String variableName;

    /** 变量缩写，如 TMP, UGRD */
    private String variableAbbrev;

    /** GRIB2 学科编号 (0=气象, 1=水文, 2=陆地, 3=空间, 10=海洋) */
    private int discipline;

    /** GRIB2 类别编号 */
    private int category;

    /** GRIB2 参数编号 */
    private int parameter;

    /** 层次类型，如 Isobaric surface, Surface, Mean sea level */
    private String levelType;

    /** 层次值（单位因 levelType 而异），如 1000, 850, 500 等 */
    private String levelValue;

    /** GRIB2 层次第一固定面类型描述 */
    private String levelType1Desc;

    /** 层次第一固定面数值 */
    private Double levelValue1;

    /** 层次第二固定面类型描述 */
    private String levelType2Desc;

    /** 层次第二固定面数值 */
    private Double levelValue2;

    /** 气压层 hPa（仅对等压面层次计算） */
    private Double pressureLevelHPa;

    /** 参考时间 (GRIB2 reference time) */
    private String referenceTime;

    /** 预报时效（小时），-1 表示分析场 */
    private int forecastHours;

    /** 网格投影类型，如 Latitude/Longitude, Lambert Conformal */
    private String gridProjection;

    /** X 方向格点数 */
    private int nx;

    /** Y 方向格点数 */
    private int ny;

    /** 数据最小值 */
    private Double dataMin;

    /** 数据最大值 */
    private Double dataMax;

    /** 数据平均值 */
    private Double dataMean;

    /** 有效网格数（非缺省值的网格点数） */
    private long dataValidCount;

    /** 总网格数 (NX × NY) */
    private long dataTotalCount;

    /** 数据填充率 = dataValidCount / dataTotalCount */
    private Double dataFillRatio;

    /** 数据单位 */
    private String unit;

    /** 网格描述中的额外说明 */
    private String description;

    // ---- 便捷方法 ----

    /** 是否为分析场（非预报） */
    public boolean isAnalysis() {
        return forecastHours <= 0;
    }

    /** 是否为气压层数据 */
    public boolean isIsobaricLevel() {
        return "Isobaric surface".equalsIgnoreCase(levelType)
                || "Isobaric surface".equalsIgnoreCase(levelType1Desc);
    }
}
