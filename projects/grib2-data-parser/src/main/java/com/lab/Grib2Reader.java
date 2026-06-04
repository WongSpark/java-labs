package com.lab;

import com.lab.model.Grib2RecordInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.ma2.Array;
import ucar.ma2.MAMath;
import ucar.nc2.Variable;
import ucar.nc2.dataset.CoordinateAxis;
import ucar.nc2.dataset.CoordinateAxis1D;
import ucar.nc2.dt.GridCoordSystem;
import ucar.nc2.dt.GridDatatype;
import ucar.nc2.dt.grid.GridDataset;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * GRIB2 文件读取器，基于 UCAR CDM (Common Data Model) 库。
 *
 * <p>核心策略：CDM 将 GRIB2 中多个气压层的同一变量合并为一个 3D/4D Grid。
 * 每个 Grid 可能包含多个层次维度（Z 轴），需要逐层读取。
 */
public class Grib2Reader {

    private static final Logger log = LoggerFactory.getLogger(Grib2Reader.class);

    public List<Grib2RecordInfo> read(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("文件不存在: " + filePath);
        }

        List<Grib2RecordInfo> records = new ArrayList<>();
        log.info("正在打开 GRIB2 文件: {}", filePath);

        try (GridDataset gridDataset = GridDataset.open(filePath)) {
            List<GridDatatype> grids = gridDataset.getGrids();
            log.info("文件中共有 {} 个 Grid 变量", grids.size());

            for (int i = 0; i < grids.size(); i++) {
                GridDatatype grid = grids.get(i);
                List<Grib2RecordInfo> levelRecords = extractAllLevels(grid, i, records.size());
                records.addAll(levelRecords);
            }
        }

        log.info("共解析 {} 条 GRIB2 记录", records.size());
        return records;
    }

    /**
     * 提取一个 Grid 的所有 Z 层记录。
     * <p>若 Grid 无 Z 维（如 Surface 变量），返回单条记录。
     * 若有 Z 维（如等压面变量），逐层读取，每条记录对应一个气压层。
     */
    private List<Grib2RecordInfo> extractAllLevels(GridDatatype grid,
                                                   int gridIndex, int startSeq) throws IOException {

        Variable var = grid.getVariable();
        String fullName = var.getFullName();
        String shortName = var.getShortName();
        GridCoordSystem cs = grid.getCoordinateSystem();

        // ---- 网格公共元数据（不随层次变化） ----
        int nx = grid.getXDimension() != null ? grid.getXDimension().getLength() : 0;
        int ny = grid.getYDimension() != null ? grid.getYDimension().getLength() : 0;

        String gridProjection = "Unknown";
        try {
            if (grid.getProjection() != null) {
                gridProjection = grid.getProjection().getName();
            }
        } catch (Exception e) {
            log.debug("提取投影信息时出错: {}", e.getMessage());
        }

        // ---- 时间信息 ----
        String referenceTime = "N/A";
        int forecastHours = -1;
        int nt = 1; // 默认 1 个时次
        try {
            CoordinateAxis timeAxis = cs.getTimeAxis();
            if (timeAxis instanceof CoordinateAxis1D) {
                nt = (int) timeAxis.getSize();
                if (nt > 0) {
                    double timeVal = ((CoordinateAxis1D) timeAxis).getCoordValue(0);
                    referenceTime = formatTimeValue(timeVal);
                    forecastHours = (int) Math.round(timeVal);
                }
            }
        } catch (Exception e) {
            log.debug("提取时间信息时出错: {}", e.getMessage());
        }

        // ---- 层次信息：确定 Z 轴与层数 ----
        CoordinateAxis1D zAxis = cs.getVerticalAxis();
        int nz = (zAxis != null) ? (int) zAxis.getSize() : 0;

        // 判断层次类型
        String levelType = inferLevelType(var, fullName, shortName, zAxis);

        // 读取所有 Z 层的坐标值
        double[] zValues;
        if (nz > 0) {
            zValues = zAxis.getCoordValues();
        } else {
            zValues = new double[]{Double.NaN};
            nz = 1; // 至少迭代一次（无 Z 维的 surface 变量）
        }

        log.info("  Grid[{}] \"{}\": Z 轴层数 = {}, X={}, Y={}",
                gridIndex, shortName, nz > 1 ? nz : nz, nx, ny);

        List<Grib2RecordInfo> records = new ArrayList<>();

        // 确定时间维度的 Index（通常为 0）
        int tIndex = 0;

        // ---- 逐层读取 ----
        for (int z = 0; z < nz; z++) {
            double zVal = zValues[z];
            Double pressureHPa = null;
            String levelValue;

            if (zAxis != null) {
                String zUnits = zAxis.getUnitsString();
                if (zUnits != null && (zUnits.contains("Pa") || zUnits.contains("hPa")
                        || zUnits.contains("mb") || zUnits.contains("millibar"))) {
                    if (zUnits.contains("hPa") || zUnits.contains("mb")
                            || zUnits.contains("millibar")) {
                        pressureHPa = zVal;
                    } else {
                        pressureHPa = zVal / 100.0; // Pa → hPa
                    }
                    levelValue = String.format("%.0f hPa", pressureHPa);
                } else {
                    levelValue = String.format("%.1f %s", zVal,
                            zUnits != null ? zUnits : "");
                }
            } else {
                levelValue = "N/A";
            }

            // 读取该层数据切片
            Grib2RecordInfo.Grib2RecordInfoBuilder builder = Grib2RecordInfo.builder()
                    .recordIndex(startSeq + z + 1)
                    .variableName(fullName != null ? fullName : shortName)
                    .variableAbbrev(shortName)
                    .unit(grid.getUnitsString())
                    .description(var.getDescription())
                    .levelType(levelType)
                    .levelValue(levelValue)
                    .levelType1Desc(levelType)
                    .levelValue1(zVal)
                    .pressureLevelHPa(pressureHPa)
                    .referenceTime(referenceTime)
                    .forecastHours(forecastHours)
                    .gridProjection(gridProjection)
                    .nx(nx)
                    .ny(ny);

            Grib2RecordInfo info = builder.build();

            // 读取该 (t, z) 切片并计算统计值
            computeStatisticsForLevel(grid, tIndex, z, info);

            records.add(info);
        }

        return records;
    }

    /**
     * 综合判断层次类型。
     */
    private String inferLevelType(Variable var, String fullName, String shortName,
                                  CoordinateAxis1D zAxis) {
        // 1. 从 Z 轴单位推断
        if (zAxis != null) {
            String zUnits = zAxis.getUnitsString();
            if (zUnits != null) {
                String u = zUnits.toLowerCase();
                if (u.contains("hpa") || u.contains("pa") || u.contains("mb")
                        || u.contains("millibar")) {
                    return "Isobaric surface";
                }
                if (u.contains("sigma") || u.contains("hybrid") || u.contains("level")) {
                    return zUnits;
                }
            }
        }

        // 2. 从属性推断
        String attrType = extractLevelTypeFromAttributes(var);
        if (attrType != null) return attrType;

        // 3. 从变量名推断
        return inferLevelTypeFromName(fullName, shortName);
    }

    private String extractLevelTypeFromAttributes(Variable var) {
        String[] attrNames = {
                "GRIB2_level_type_desc", "GRIB2_level_type",
                "GRIB_level_type_desc", "GRIB_level_type",
                "level_type_desc", "level_type"
        };
        for (String attrName : attrNames) {
            if (var.findAttribute(attrName) != null) {
                String val = var.findAttribute(attrName).getStringValue();
                if (val != null && !val.isEmpty()) return val;
            }
        }
        return null;
    }

    private String inferLevelTypeFromName(String fullName, String shortName) {
        String combined = ((fullName != null ? fullName : "")
                + " " + (shortName != null ? shortName : "")).toLowerCase();
        if (combined.contains("isobaric") || combined.contains("pressure_level")
                || combined.contains("pressure level")) return "Isobaric surface";
        if (combined.contains("surface") && !combined.contains("isobaric")) return "Surface";
        if (combined.contains("mean_sea") || combined.contains("mean sea")
                || combined.contains("msl")) return "Mean sea level";
        if (combined.contains("tropopause")) return "Tropopause";
        if (combined.contains("max_wind") || combined.contains("max wind")) return "Max wind level";
        if (combined.contains("sigma")) return "Sigma level";
        if (combined.contains("potential_vorticity") || combined.contains("pv")
                || combined.contains("potential vorticity")) return "Potential vorticity surface";
        return "Unknown";
    }

    /**
     * 读取 Grid 在指定 (t, z) 处的二维切片并计算统计值。
     */
    private void computeStatisticsForLevel(GridDatatype grid, int t, int z,
                                           Grib2RecordInfo info) {
        try {
            // readDataSlice(t, z, y, x): y=-1/x=-1 表示读取整个 YX 平面
            Array data = grid.readDataSlice(t, z, -1, -1);
            if (data == null || data.getSize() == 0) return;

            MAMath.MinMax minMax = grid.getMinMaxSkipMissingData(data);
            if (minMax != null) {
                info.setDataMin(minMax.min);
                info.setDataMax(minMax.max);
            }

            float[] fdata = (float[]) data.get1DJavaArray(float.class);
            long totalCount = fdata.length;
            double sum = 0.0;
            long validCount = 0;
            for (float v : fdata) {
                if (Float.isNaN(v) || grid.isMissingData(v)) continue;
                sum += v;
                validCount++;
            }

            info.setDataTotalCount(totalCount);
            info.setDataValidCount(validCount);
            info.setDataFillRatio(totalCount > 0
                    ? validCount * 100.0 / totalCount : 0.0);

            if (validCount > 0) {
                info.setDataMean(sum / validCount);
            }
        } catch (Exception e) {
            log.debug("统计计算失败 [t={}, z={}]: {}", t, z, e.getMessage());
        }
    }

    private String formatTimeValue(double timeVal) {
        long hours = Math.round(timeVal);
        if (Math.abs(hours) > 1_000_000) {
            hours = Math.round(timeVal / 3600.0);
        }
        return String.format("%+d hours", hours);
    }
}
