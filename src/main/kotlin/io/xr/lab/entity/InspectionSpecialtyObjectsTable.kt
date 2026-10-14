// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionSpecialtyObjects。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionSpecialtyObjectsTable : Table("inspection_specialty_objects") {
  val inspectionSpecialtyCode = text("inspection_specialty_code")
  val inspectionObjectCode = text("inspection_object_code")
  val remark = text("remark").nullable()
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(inspectionSpecialtyCode, inspectionObjectCode)
}
