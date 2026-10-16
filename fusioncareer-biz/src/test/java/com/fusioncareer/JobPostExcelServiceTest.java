package com.fusioncareer;

import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.WorkMode;
import com.fusioncareer.service.JobPostExcelService;
import com.fusioncareer.service.impl.JobPostExcelServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobPostExcelServiceTest {

    private final JobPostExcelService readService = new JobPostExcelServiceImpl();

    @Test
    void parseSampleLayoutAndMergedCells() throws Exception {
        byte[] readFile;
        try (Workbook createWorkbook = new XSSFWorkbook();
             ByteArrayOutputStream createBytes = new ByteArrayOutputStream()) {
            Sheet createSheet = createWorkbook.createSheet("岗位表");
            Row createHeader = createSheet.createRow(0);
            String[] createHeaders = {"单位名称", "岗位名称", "实习岗位", "需求人数", "岗位职责", "招聘要求",
                    "实习类别\n大实习岗位/小实习岗位/均可", "实习时间及频次要求", "职场发展方向",
                    "实习薪资/相关补贴保障\n（不对外）", "线上/线下实习", "实习地点/单位地址",
                    "实习对接联系人\n（不对外）", "实习对接联系人电话\n（不对外）", "备注\n（不对外）"};
            for (int i = 0; i < createHeaders.length; i++) createHeader.createCell(i).setCellValue(createHeaders[i]);
            Row createFirst = createSheet.createRow(1);
            createFirst.createCell(0).setCellValue("示例单位");
            createFirst.createCell(1).setCellValue("编辑部");
            createFirst.createCell(2).setCellValue("采编实习生");
            createFirst.createCell(3).setCellValue("2人");
            createFirst.createCell(4).setCellValue("采写编评");
            createFirst.createCell(5).setCellValue("本科及以上");
            createFirst.createCell(6).setCellValue("大实习岗位");
            createFirst.createCell(7).setCellValue("每周3天，3个月以上");
            createFirst.createCell(8).setCellValue("记者方向");
            createFirst.createCell(9).setCellValue("150元/天");
            createFirst.createCell(10).setCellValue("线上线下相结合");
            createFirst.createCell(11).setCellValue("上海市杨浦区");
            createFirst.createCell(12).setCellValue("张老师");
            createFirst.createCell(13).setCellValue("13800000000");
            Row createSecond = createSheet.createRow(2);
            createSecond.createCell(1).setCellValue("视频部");
            createSecond.createCell(2).setCellValue("视频实习生");
            createSecond.createCell(3).setCellValue("1-2人");
            createSecond.createCell(6).setCellValue("均可");
            createSheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
            createWorkbook.write(createBytes);
            readFile = createBytes.toByteArray();
        }

        List<JobPostRequest> readJobs = readService.parse(new MockMultipartFile(
                "file", "岗位表.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", readFile));

        assertThat(readJobs).hasSize(2);
        assertThat(readJobs.get(0)).satisfies(readJob -> {
            assertThat(readJob.getCompanyName()).isEqualTo("示例单位");
            assertThat(readJob.getDepartment()).isEqualTo("编辑部");
            assertThat(readJob.getPositionName()).isEqualTo("采编实习生");
            assertThat(readJob.getHeadcount()).isEqualTo(2);
            assertThat(readJob.getRecruitType()).isEqualTo(RecruitType.BIG_INTERNSHIP);
            assertThat(readJob.getWorkMode()).isEqualTo(WorkMode.HYBRID);
            assertThat(readJob.getInternalCompensation()).isEqualTo("150元/天");
            assertThat(readJob.getContactName()).isEqualTo("张老师");
            assertThat(readJob.getStatus()).isEqualTo(JobPostStatus.PUBLISHED);
        });
        assertThat(readJobs.get(1).getCompanyName()).isEqualTo("示例单位");
        assertThat(readJobs.get(1).getHeadcount()).isEqualTo(2);
        assertThat(readJobs.get(1).getHeadcountDisplay()).isEqualTo("1-2人");
        assertThat(readJobs.get(1).getRecruitType()).isEqualTo(RecruitType.BOTH_INTERNSHIP);
    }

    @Test
    void generatedTemplateCanBeRead() throws Exception {
        byte[] readTemplate = readService.buildTemplate();
        try (Workbook readWorkbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(readTemplate))) {
            assertThat(readWorkbook.getSheet("岗位导入").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("单位名称*");
            assertThat(readWorkbook.getSheet("岗位导入").getRow(0).getCell(9).getStringCellValue())
                    .contains("不对外");
        }
    }
}
