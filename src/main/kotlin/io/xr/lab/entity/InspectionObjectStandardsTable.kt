// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionObjectStandards。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionObjectStandardsTable : Table("inspection_object_standards") {
  val inspectionObjectCode = text("inspection_object_code")
  val inspectionStandardCode = text("inspection_standard_code")
  val role = text("role")
  val remark = text("remark").nullable()
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(inspectionObjectCode, inspectionStandardCode, role)
}
