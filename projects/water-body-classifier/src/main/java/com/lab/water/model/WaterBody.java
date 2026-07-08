package com.lab.water.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.MultiPolygon;

/**
 * 水体要素模型 — 对应 SHP 中一个 Feature。
 * 存储几何信息、形态指标及分类结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaterBody {

    /** 原始 FID / 唯一标识 */
    private long fid;

    /** 水体名称（如果有） */
    private String name;

    /** 几何形状（读取自 SHP） */
    private MultiPolygon geometry;

    // ========== 形态指标 ==========

    /** 周长（m） */
    private double perimeter;

    /** 面积（m²） */
    private double area;

    /** 面积（km²） */
    private double areaSqKm;

    /** 椭圆度 = π * (长轴/2)² / 面积，越接近 1 越圆 */
    private double ellipticity;

    /** 狭长度 = 长轴 / 短轴，越大越狭长 */
    private double slenderness;

    /** 最小外接矩形长轴（m） */
    private double majorAxis;

    /** 最小外接矩形短轴（m） */
    private double minorAxis;

    // ========== 分类结果 ==========

    /** 水体类型：LAKE / RIVER / OCEAN */
    private WaterBodyType waterType;

    /** 层级编号（如 1=大洋, 2=大湖, 3=中型湖, 4=小湖/河流段） */
    private int hierarchyLevel;

    /** 层级标签（如 "Level-1", "Level-2"） */
    private String hierarchyLabel;
}
