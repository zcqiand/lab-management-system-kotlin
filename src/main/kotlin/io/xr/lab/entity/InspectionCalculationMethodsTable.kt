// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionCalculationMethods。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionCalculationMethodsTable : Table("inspection_calculation_methods") {
  val inspectionObjectCode = text("inspection_object_code")
  val inspectionParameterCode = text("inspection_parameter_code")
  val testingStandardCode = text("testing_standard_code").nullable()
  val reportNameCode = text("report_name_code").nullable()
  val algorithmType = text("algorithm_type")
  val specimenCount = integer("specimen_count")
  val formula = text("formula").nullable()
  val conditions = text("conditions").nullable()
  val roundingRule = text("rounding_rule").nullable()
  val remark = text("remark").nullable()
  val sortOrder = integer("sort_order")
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(inspectionObjectCode, inspectionParameterCode)
}
