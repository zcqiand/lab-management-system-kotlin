// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts samples。
package io.xr.lab.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.jsonb

object SamplesTable : Table("samples") {
  val id = text("id")
  val receiptId = text("receipt_id")
  val sampleCode = text("sample_code")
  val sampleName = text("sample_name").nullable()
  val model = text("model").nullable()
  val specification = text("specification").nullable()
  val grade = text("grade").nullable()
  val brand = text("brand").nullable()
  val manufacturer = text("manufacturer").nullable()
  val structuralPart = text("structural_part").nullable()
  val representQuantity = text("represent_quantity").nullable()
  val sampleQuantity = text("sample_quantity").nullable()
  val batchNumber = text("batch_number").nullable()
  val supplyUnit = text("supply_unit").nullable()
  val arrivalDate = text("arrival_date").nullable()
  val samplingDate = text("sampling_date").nullable()
  val curingCondition = text("curing_condition").nullable()
  val age = text("age").nullable()
  val ext = jsonb<JsonElement>("ext", Json)
  val remark = text("remark").nullable()
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  val tenantId = text("tenant_id")
  override val primaryKey = PrimaryKey(id)
}
