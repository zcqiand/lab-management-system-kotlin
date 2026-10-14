// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionParamInterfaces。
package io.xr.lab.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.jsonb

object InspectionParamInterfacesTable : Table("inspection_param_interfaces") {
  val code = text("code")
  val name = text("name").nullable()
  val componentPath = text("component_path")
  val description = text("description").nullable()
  val isOfficial = bool("is_official").nullable()
  val sortOrder = integer("sort_order")
  val config = jsonb<JsonElement>("config", Json).nullable()
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(code)
}
