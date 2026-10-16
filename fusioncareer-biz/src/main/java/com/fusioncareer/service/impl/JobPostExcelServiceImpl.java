package com.fusioncareer.service.impl;

import com.fusioncareer.service.JobPostExcelService;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.JobSubCategory;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.SourceType;
import com.fusioncareer.enums.WorkMode;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JobPostExcelServiceImpl implements JobPostExcelService {

    private static final Pattern FIRST_INTEGER = Pattern.compile("\\d+");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"));
    private static final List<String> TEMPLATE_HEADERS = List.of(
            "单位名称*", "部门", "岗位名称（发布标题）*", "需求人数", "岗位职责", "招聘要求",
            "实习类别", "实习时间及频次要求", "职场发展方向", "实习薪资/相关补贴保障（不对外）",
            "线上/线下实习", "实习地点/单位地址", "联系人（不对外）", "联系方式（不对外）",
            "备注（不对外）", "岗位大类", "岗位二级分类", "工作省份", "工作城市",
            "投递截止日期", "公开薪资", "信息源链接");

    private final DataFormatter formatter = new DataFormatter();

    /** 兼容平台模板和学院提供的“岗位需求总表”。 */
    @Override
    public List<JobPostRequest> parse(MultipartFile readFile) {
        if (readFile == null || readFile.isEmpty()) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "请选择 Excel 文件");
        }
        try (Workbook readWorkbook = WorkbookFactory.create(readFile.getInputStream())) {
            Header readHeader = findHeader(readWorkbook);
            List<JobPostRequest> createJobs = new ArrayList<>();
            List<String> readErrors = new ArrayList<>();
            String readPreviousCompany = null;
            RecruitType readDefaultRecruitType = recruitTypeFromFilename(readFile.getOriginalFilename());
            Sheet readSheet = readHeader.sheet();
            boolean readSampleLayout = readHeader.columns().containsKey("实习岗位");

            for (int readRowIndex = readHeader.rowIndex() + 1;
                 readRowIndex <= readSheet.getLastRowNum(); readRowIndex++) {
                Row readRow = readSheet.getRow(readRowIndex);
                if (readRow == null || isBlankRow(readSheet, readRow, readHeader.columns())) {
                    continue;
                }
                String readCompany = value(readSheet, readRow, readHeader.columns(), "单位名称", "单位名称*");
                if (StringUtils.hasText(readCompany)) {
                    readPreviousCompany = readCompany;
                } else {
                    readCompany = readPreviousCompany;
                }
                String readPosition = readSampleLayout
                        ? value(readSheet, readRow, readHeader.columns(), "实习岗位")
                        : value(readSheet, readRow, readHeader.columns(), "岗位名称（发布标题）*", "岗位名称（发布标题）", "工作岗位名称", "岗位名称");
                int readExcelRow = readRowIndex + 1;
                if (!StringUtils.hasText(readCompany)) {
                    readErrors.add("第" + readExcelRow + "行：单位名称不能为空");
                }
                if (!StringUtils.hasText(readPosition)) {
                    readErrors.add("第" + readExcelRow + "行：岗位名称不能为空");
                }
                if (!StringUtils.hasText(readCompany) || !StringUtils.hasText(readPosition)) {
                    continue;
                }

                JobPostRequest createJob = new JobPostRequest();
                createJob.setSourceType(SourceType.PLATFORM);
                createJob.setStatus(JobPostStatus.PUBLISHED);
                createJob.setRecommended(false);
                createJob.setCompanyName(readCompany);
                createJob.setDepartment(readSampleLayout
                        ? value(readSheet, readRow, readHeader.columns(), "岗位名称")
                        : value(readSheet, readRow, readHeader.columns(), "部门", "工作部门"));
                createJob.setPositionName(readPosition);
                String readHeadcount = value(readSheet, readRow, readHeader.columns(), "需求人数");
                createJob.setHeadcountDisplay(readHeadcount);
                createJob.setHeadcount(parseHeadcount(readHeadcount, readExcelRow, readErrors));
                createJob.setJobDesc(value(readSheet, readRow, readHeader.columns(), "岗位职责"));
                createJob.setReqOther(value(readSheet, readRow, readHeader.columns(), "招聘要求"));
                createJob.setRecruitType(parseRecruitType(
                        valueByPrefix(readSheet, readRow, readHeader.columns(), "实习类别"), readDefaultRecruitType));
                createJob.setWorkTimeRequirement(value(readSheet, readRow, readHeader.columns(), "实习时间及频次要求"));
                createJob.setCareerDirection(value(readSheet, readRow, readHeader.columns(), "职场发展方向"));
                createJob.setInternalCompensation(valueByPrefix(readSheet, readRow, readHeader.columns(), "实习薪资/相关补贴保障"));
                createJob.setWorkMode(parseWorkMode(valueByPrefix(readSheet, readRow, readHeader.columns(), "线上/线下实习")));
                createJob.setWorkLocation(valueByPrefix(readSheet, readRow, readHeader.columns(), "实习地点/单位地址"));
                createJob.setContactName(value(readSheet, readRow, readHeader.columns(),
                        "联系人（不对外）", "实习对接联系人（不对外）", "实习对接联系人"));
                createJob.setContactInfo(value(readSheet, readRow, readHeader.columns(),
                        "联系方式（不对外）", "实习对接联系人电话（不对外）", "实习对接联系人电话"));
                createJob.setInternalRemark(value(readSheet, readRow, readHeader.columns(), "备注（不对外）", "备注"));
                createJob.setJobCategory(parseCategory(value(readSheet, readRow, readHeader.columns(), "岗位大类")));
                createJob.setJobSubCategory(parseSubCategory(
                        value(readSheet, readRow, readHeader.columns(), "岗位二级分类"), createJob.getJobCategory()));
                createJob.setWorkProvince(value(readSheet, readRow, readHeader.columns(), "工作省份"));
                createJob.setWorkCity(value(readSheet, readRow, readHeader.columns(), "工作城市"));
                createJob.setApplicationDeadline(parseDate(readSheet, readRow, readHeader.columns(), "投递截止日期",
                        readExcelRow, readErrors));
                createJob.setSalaryDisplay(value(readSheet, readRow, readHeader.columns(), "公开薪资"));
                createJob.setSourceUrl(value(readSheet, readRow, readHeader.columns(), "信息源链接"));
                createJobs.add(createJob);
            }
            if (!readErrors.isEmpty()) {
                throw ServiceException.of(ResultCode.VALIDATE_FAILED,
                        String.join("；", readErrors.subList(0, Math.min(readErrors.size(), 10))));
            }
            if (createJobs.isEmpty()) {
                throw ServiceException.of(ResultCode.VALIDATE_FAILED, "表格中没有可导入的岗位数据");
            }
            return createJobs;
        } catch (ServiceException readError) {
            throw readError;
        } catch (Exception readError) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "Excel 文件无法读取，请使用 .xlsx 或 .xls 格式");
        }
    }

    @Override
    public byte[] buildTemplate() {
        try (Workbook createWorkbook = new XSSFWorkbook();
             ByteArrayOutputStream createBytes = new ByteArrayOutputStream()) {
            Sheet createSheet = createWorkbook.createSheet("岗位导入");
            createSheet.createFreezePane(0, 1);
            Row createHeader = createSheet.createRow(0);
            createHeader.setHeightInPoints(32);
            CellStyle createStyle = createWorkbook.createCellStyle();
            createStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            createStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            createStyle.setWrapText(true);
            Font createFont = createWorkbook.createFont();
            createFont.setBold(true);
            createFont.setColor(IndexedColors.WHITE.getIndex());
            createStyle.setFont(createFont);
            for (int createIndex = 0; createIndex < TEMPLATE_HEADERS.size(); createIndex++) {
                Cell createCell = createHeader.createCell(createIndex);
                createCell.setCellValue(TEMPLATE_HEADERS.get(createIndex));
                createCell.setCellStyle(createStyle);
                createSheet.setColumnWidth(createIndex, Math.min(50, Math.max(14,
                        TEMPLATE_HEADERS.get(createIndex).length() * 2)) * 256);
            }
            createSheet.setAutoFilter(new CellRangeAddress(0, 0, 0, TEMPLATE_HEADERS.size() - 1));
            createWorkbook.write(createBytes);
            return createBytes.toByteArray();
        } catch (IOException readError) {
            throw new IllegalStateException("生成岗位导入模板失败", readError);
        }
    }

    private Header findHeader(Workbook readWorkbook) {
        for (Sheet readSheet : readWorkbook) {
            int readLimit = Math.min(readSheet.getLastRowNum(), 20);
            for (int readRowIndex = 0; readRowIndex <= readLimit; readRowIndex++) {
                Row readRow = readSheet.getRow(readRowIndex);
                if (readRow == null) continue;
                Map<String, Integer> readColumns = new HashMap<>();
                for (Cell readCell : readRow) {
                    String readName = normalizeHeader(formatter.formatCellValue(readCell));
                    if (StringUtils.hasText(readName)) readColumns.put(readName, readCell.getColumnIndex());
                }
                boolean readHasCompany = readColumns.containsKey("单位名称") || readColumns.containsKey("单位名称*");
                boolean readHasPosition = readColumns.containsKey("实习岗位")
                        || readColumns.containsKey("岗位名称")
                        || readColumns.containsKey("岗位名称（发布标题）")
                        || readColumns.containsKey("岗位名称（发布标题）*");
                if (readHasCompany && readHasPosition) return new Header(readSheet, readRowIndex, readColumns);
            }
        }
        throw ServiceException.of(ResultCode.VALIDATE_FAILED, "未找到表头，请下载并使用岗位导入模板");
    }

    private boolean isBlankRow(Sheet readSheet, Row readRow, Map<String, Integer> readColumns) {
        return readColumns.values().stream().allMatch(readColumn ->
                !StringUtils.hasText(cellValue(readSheet, readRow.getRowNum(), readColumn)));
    }

    private String value(Sheet readSheet, Row readRow, Map<String, Integer> readColumns, String... readNames) {
        for (String readName : readNames) {
            Integer readColumn = readColumns.get(normalizeHeader(readName));
            if (readColumn != null) return clean(cellValue(readSheet, readRow.getRowNum(), readColumn));
        }
        return null;
    }

    private String valueByPrefix(Sheet readSheet, Row readRow, Map<String, Integer> readColumns, String readPrefix) {
        String readNormalized = normalizeHeader(readPrefix);
        return readColumns.entrySet().stream()
                .filter(readEntry -> readEntry.getKey().startsWith(readNormalized))
                .findFirst()
                .map(readEntry -> clean(cellValue(readSheet, readRow.getRowNum(), readEntry.getValue())))
                .orElse(null);
    }

    private String cellValue(Sheet readSheet, int readRow, int readColumn) {
        for (CellRangeAddress readRange : readSheet.getMergedRegions()) {
            if (readRange.isInRange(readRow, readColumn)) {
                readRow = readRange.getFirstRow();
                readColumn = readRange.getFirstColumn();
                break;
            }
        }
        Row readActualRow = readSheet.getRow(readRow);
        Cell readCell = readActualRow == null ? null : readActualRow.getCell(readColumn);
        return readCell == null ? null : formatter.formatCellValue(readCell);
    }

    private Integer parseHeadcount(String readValue, int readRow, List<String> updateErrors) {
        if (!StringUtils.hasText(readValue)) return null;
        if (readValue.contains("若干") || readValue.contains("不限")) return null;
        Matcher readMatcher = FIRST_INTEGER.matcher(readValue);
        if (!readMatcher.find()) {
            updateErrors.add("第" + readRow + "行：需求人数应包含数字");
            return null;
        }
        int readHeadcount = Integer.parseInt(readMatcher.group());
        while (readMatcher.find()) readHeadcount = Integer.parseInt(readMatcher.group());
        return readHeadcount;
    }

    private LocalDate parseDate(Sheet readSheet, Row readRow, Map<String, Integer> readColumns,
                                String readName, int readExcelRow, List<String> updateErrors) {
        Integer readColumn = readColumns.get(normalizeHeader(readName));
        if (readColumn == null) return null;
        Cell readCell = readRow.getCell(readColumn);
        if (readCell == null || !StringUtils.hasText(formatter.formatCellValue(readCell))) return null;
        if (readCell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(readCell)) {
            return readCell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String readValue = clean(formatter.formatCellValue(readCell));
        for (DateTimeFormatter readFormat : DATE_FORMATS) {
            try {
                return LocalDate.parse(readValue, readFormat);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个支持的日期格式。
            }
        }
        updateErrors.add("第" + readExcelRow + "行：投递截止日期格式应为 yyyy-MM-dd");
        return null;
    }

    private RecruitType parseRecruitType(String readValue, RecruitType readDefault) {
        if (!StringUtils.hasText(readValue)) return readDefault;
        if (readValue.contains("均可") || (readValue.contains("大") && readValue.contains("小"))) {
            return RecruitType.BOTH_INTERNSHIP;
        }
        if (readValue.contains("大实习")) return RecruitType.BIG_INTERNSHIP;
        if (readValue.contains("小实习")) return RecruitType.SMALL_INTERNSHIP;
        if (readValue.contains("日常")) return RecruitType.DAILY_INTERNSHIP;
        return RecruitType.OTHER;
    }

    private RecruitType recruitTypeFromFilename(String readFilename) {
        if (readFilename != null && readFilename.contains("小实习")) return RecruitType.SMALL_INTERNSHIP;
        if (readFilename != null && readFilename.contains("大实习")) return RecruitType.BIG_INTERNSHIP;
        return RecruitType.OTHER;
    }

    private WorkMode parseWorkMode(String readValue) {
        if (!StringUtils.hasText(readValue)) return null;
        if ((readValue.contains("线上") && readValue.contains("线下")) || readValue.contains("结合") || readValue.contains("均可")) {
            return WorkMode.HYBRID;
        }
        if (readValue.contains("线上")) return WorkMode.ONLINE;
        if (readValue.contains("线下")) return WorkMode.OFFLINE;
        return null;
    }

    private JobCategory parseCategory(String readValue) {
        return Arrays.stream(JobCategory.values())
                .filter(readValue == null ? readItem -> readItem == JobCategory.OTHER
                        : readItem -> readValue.equalsIgnoreCase(readItem.name())
                        || readValue.equals(String.valueOf(readItem.getCode()))
                        || readValue.trim().equals(readItem.getDesc().trim()))
                .findFirst().orElse(JobCategory.OTHER);
    }

    private JobSubCategory parseSubCategory(String readValue, JobCategory readCategory) {
        return Arrays.stream(JobSubCategory.values())
                .filter(readItem -> StringUtils.hasText(readValue)
                        && (readValue.equalsIgnoreCase(readItem.name())
                        || readValue.equals(String.valueOf(readItem.getCode()))
                        || readValue.trim().equals(readItem.getDesc().trim())))
                .filter(readItem -> readItem.getParent() == readCategory)
                .findFirst().orElse(readCategory == JobCategory.OTHER ? JobSubCategory.OTHER : null);
    }

    private String normalizeHeader(String readValue) {
        return readValue == null ? "" : readValue.replaceAll("\\s+", "").trim();
    }

    private String clean(String readValue) {
        if (!StringUtils.hasText(readValue)) return null;
        String readCleaned = readValue.trim();
        return "/".equals(readCleaned) ? null : readCleaned;
    }

    private record Header(Sheet sheet, int rowIndex, Map<String, Integer> columns) {}
}
