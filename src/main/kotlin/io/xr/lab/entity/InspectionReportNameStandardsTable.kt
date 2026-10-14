// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionReportNameStandards。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionReportNameStandardsTable : Table("inspection_report_name_standards") {
  val reportNameCode = text("report_name_code")
  val inspectionStandardCode = text("inspection_standard_code")
  val role = text("role")
  val remark = text("remark").nullable()
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(reportNameCode, inspectionStandardCode, role)
}
