// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts contracts。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object ContractsTable : Table("contracts") {
  val id = text("id")
  val contractCode = text("contract_code")
  val clientUnit = text("client_unit")
  val projectName = text("project_name")
  val projectLocation = text("project_location").nullable()
  val constructionUnit = text("construction_unit")
  val inspectionSpecialtyCode = text("inspection_specialty_code").nullable()
  val buildingUnit = text("building_unit").nullable()
  val supervisorUnit = text("supervisor_unit").nullable()
  val inspectionPerson = text("inspection_person").nullable()
  val inspectionPhone = text("inspection_phone").nullable()
  val witnessUnit = text("witness_unit")
  val witness = text("witness")
  val witnessPhone = text("witness_phone").nullable()
  val contactPerson = text("contact_person").nullable()
  val contactPhone = text("contact_phone").nullable()
  val entrustedDate = text("entrusted_date").nullable()
  val status = text("status")
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  val tenantId = text("tenant_id")
  override val primaryKey = PrimaryKey(id)
}
