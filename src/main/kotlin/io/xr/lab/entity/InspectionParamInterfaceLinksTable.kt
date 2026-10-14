// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionParamInterfaceLinks。
package io.xr.lab.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.jsonb

object InspectionParamInterfaceLinksTable : Table("inspection_param_interface_links") {
  val inspectionParameterCode = text("inspection_parameter_code")
  val paramInterfaceCode = text("param_interface_code")
  val reportNameCode = text("report_name_code").nullable()
  val config = jsonb<JsonElement>("config", Json).nullable()
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(inspectionParameterCode, paramInterfaceCode)
}
