// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionTechnicalRequirements。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionTechnicalRequirementsTable : Table("inspection_technical_requirements") {
  val inspectionObjectCode = text("inspection_object_code")
  val inspectionParameterCode = text("inspection_parameter_code")
  val judgmentStandardCode = text("judgment_standard_code")
  val conditions = text("conditions").nullable()
  val valueType = text("value_type")
  val minValue = integer("min_value").nullable()
  val maxValue = integer("max_value").nullable()
  val targetValue = text("target_value").nullable()
  val expression = text("expression").nullable()
  val unit = text("unit").nullable()
  val comparison = text("comparison")
  val judgmentMode = text("judgment_mode")
  val verificationStatus = text("verification_status")
  val clause = text("clause").nullable()
  val sourcePage = integer("source_page").nullable()
  val sourceHash = text("source_hash").nullable()
  val brand = text("brand").nullable()
  val model = text("model").nullable()
  val grade = text("grade").nullable()
  val spec = text("spec").nullable()
  val sieve = text("sieve").nullable()
  val remark = text("remark").nullable()
  val sortOrder = integer("sort_order")
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  val tenantId = text("tenant_id")
  override val primaryKey = PrimaryKey(inspectionObjectCode, inspectionParameterCode, judgmentStandardCode)
}
