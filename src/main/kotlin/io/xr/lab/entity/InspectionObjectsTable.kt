// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionObjects。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionObjectsTable : Table("inspection_objects") {
  val code = text("code")
  val inspectionSpecialtyCode = text("inspection_specialty_code")
  val sourceProjectNo = text("source_project_no")
  val sourceProjectName = text("source_project_name")
  val name = text("name")
  val isOptionalForQualification = bool("is_optional_for_qualification")
  val isOfficial = bool("is_official")
  val enabled = bool("enabled")
  val sortOrder = integer("sort_order")
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(code)
}
