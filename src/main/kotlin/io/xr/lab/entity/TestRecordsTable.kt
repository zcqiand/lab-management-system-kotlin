// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts testRecords。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object TestRecordsTable : Table("test_records") {
  val id = text("id")
  val sampleId = text("sample_id")
  val parameterCode = text("parameter_code")
  val standardCode = text("standard_code").nullable()
  val requirementCode = text("requirement_code").nullable()
  val requirement = text("requirement")
  val result = text("result")
  val verdict = text("verdict").nullable()
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  val tenantId = text("tenant_id")
  override val primaryKey = PrimaryKey(id)
}
