// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts sampleReceipts。
package io.xr.lab.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

object SampleReceiptsTable : Table("sample_receipts") {
  val id = text("id")
  val contractId = text("contract_id")
  val commissionCode = text("commission_code")
  val commissionDate = text("commission_date")
  val commissionRegisterCode = text("commission_register_code").nullable()
  val commissionRegisterDate = text("commission_register_date").nullable()
  val categoryCode = text("category_code")
  val projectName = text("project_name").nullable()
  val clientUnit = text("client_unit").nullable()
  val buildingUnit = text("building_unit").nullable()
  val supervisorUnit = text("supervisor_unit").nullable()
  val constructionUnit = text("construction_unit").nullable()
  val witnessUnit = text("witness_unit").nullable()
  val samplingLocation = text("sampling_location").nullable()
  val witness = text("witness").nullable()
  val witnessPhone = text("witness_phone").nullable()
  val inspector = text("inspector").nullable()
  val inspectorPhone = text("inspector_phone").nullable()
  val receivedBy = text("received_by")
  val sampleSource = text("sample_source")
  val testCategory = text("test_category")
  val testEnvironment = text("test_environment").nullable()
  val mainEquipment = text("main_equipment").nullable()
  val testOperator = text("test_operator").nullable()
  val testStartDate = text("test_start_date").nullable()
  val testEndDate = text("test_end_date").nullable()
  val originalRecordNo = text("original_record_no").nullable()
  val remark = text("remark").nullable()
  val judgmentBasis = jsonb<JsonElement>("judgment_basis", Json).nullable()
  val testingBasis = jsonb<JsonElement>("testing_basis", Json).nullable()
  val testParameters = jsonb<JsonElement>("test_parameters", Json).nullable()
  val flowStatus = text("flow_status")
  val flowHistory = jsonb<JsonElement>("flow_history", Json)
  val lastSubmittedBy = text("last_submitted_by").nullable()
  val assigneeId = text("assignee_id").nullable()
  val assigneeName = text("assignee_name").nullable()
  val plannedTestDate = text("planned_test_date").nullable()
  val reportCode = text("report_code").nullable()
  val reportDate = text("report_date").nullable()
  val conclusion = text("conclusion").nullable()
  val result = text("result").nullable()
  val issuedAt = timestampWithTimeZone("issued_at").nullable()
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  val tenantId = text("tenant_id")
  val ext = jsonb<JsonElement>("ext", Json)
  override val primaryKey = PrimaryKey(id)
}
