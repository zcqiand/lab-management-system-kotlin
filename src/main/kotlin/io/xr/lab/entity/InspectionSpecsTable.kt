// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionSpecs。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionSpecsTable : Table("inspection_specs") {
  val code = text("code")
  val inspectionObjectCode = text("inspection_object_code").nullable()
  val name = text("name")
  val remark = text("remark").nullable()
  val sortOrder = integer("sort_order")
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  val tenantId = text("tenant_id")
  override val primaryKey = PrimaryKey(code)
}
