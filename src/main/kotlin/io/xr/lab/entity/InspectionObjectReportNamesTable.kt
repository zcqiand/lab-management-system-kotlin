// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionObjectReportNames。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionObjectReportNamesTable : Table("inspection_object_report_names") {
  val inspectionObjectCode = text("inspection_object_code")
  val reportNameCode = text("report_name_code")
  val remark = text("remark").nullable()
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(inspectionObjectCode, reportNameCode)
}
