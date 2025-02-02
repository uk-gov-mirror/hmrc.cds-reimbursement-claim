/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.cdsreimbursementclaim.services.ccs

import akka.actor.ActorSystem
import akka.util.Timeout
import cats.data.EitherT
import cats.instances.future._
import org.scalamock.handlers.{CallHandler0, CallHandler1, CallHandler2}
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.test.Helpers.await
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.cdsreimbursementclaim.connectors.CcsConnector
import uk.gov.hmrc.cdsreimbursementclaim.models
import uk.gov.hmrc.cdsreimbursementclaim.models.Error
import uk.gov.hmrc.cdsreimbursementclaim.models.ccs.CcsSubmissionPayload
import uk.gov.hmrc.cdsreimbursementclaim.models.claim.CompleteClaim.CompleteC285Claim
import uk.gov.hmrc.cdsreimbursementclaim.models.claim.SupportingEvidenceAnswer.CompleteSupportingEvidenceAnswer
import uk.gov.hmrc.cdsreimbursementclaim.models.claim.{SubmitClaimRequest, SubmitClaimResponse, SupportingEvidence}
import uk.gov.hmrc.cdsreimbursementclaim.models.generators.CcsSubmissionGen._
import uk.gov.hmrc.cdsreimbursementclaim.models.generators.ClaimGen._
import uk.gov.hmrc.cdsreimbursementclaim.models.generators.CompleteClaimGen._
import uk.gov.hmrc.cdsreimbursementclaim.models.generators.Generators.sample
import uk.gov.hmrc.cdsreimbursementclaim.models.generators.UpscanGen._
import uk.gov.hmrc.cdsreimbursementclaim.models.ids.UUIDGenerator
import uk.gov.hmrc.cdsreimbursementclaim.repositories.ccs.CcsSubmissionRepo
import uk.gov.hmrc.cdsreimbursementclaim.utils.{TimeUtils, toUUIDString}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.workitem._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class CcsSubmissionServiceSpec() extends AnyWordSpec with Matchers with MockFactory {

  implicit val timeout: Timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  val executionContext: ExecutionContextExecutor = ExecutionContext.global

  val mockCcsSubmissionRepo: CcsSubmissionRepo = mock[CcsSubmissionRepo]
  val mockCcsConnector: CcsConnector           = mock[CcsConnector]
  val mockUUIDGenerator: UUIDGenerator         = mock[UUIDGenerator]

  implicit val hc: HeaderCarrier = HeaderCarrier()

  def makeDec64XmlPayload(
    correlationId: String,
    batchId: String,
    batchSize: Long,
    batchCount: Long,
    checksum: String,
    fileSize: Long,
    caseReference: String,
    eori: String,
    declarationId: String,
    declarationType: String,
    applicationName: String,
    documentType: String,
    documentReceivedDate: String,
    sourceLocation: String,
    sourceFileName: String,
    sourceFileMimeType: String,
    destinationSystem: String
  ): String =
    s"""
       |<?xml version='1.0' encoding='UTF-8'?>
       |<ans1:Envelope xmlns:ans1="http://schemas.xmlsoap.org/soap/envelope/">
       |    <ans1:Body>
       |        <ans2:BatchFileInterfaceMetadata xmlns:ans2="http://www.hmrc.gsi.gov.uk/mdg/batchFileInterfaceMetadataSchema">
       |            <ans2:sourceSystem>TPI</ans2:sourceSystem>
       |            <ans2:sourceSystemType>AWS</ans2:sourceSystemType>
       |            <ans2:interfaceName>DEC64</ans2:interfaceName>
       |            <ans2:interfaceVersion>1.0.0</ans2:interfaceVersion>
       |            <ans2:correlationID>$correlationId</ans2:correlationID>
       |            <ans2:batchID>$batchId</ans2:batchID>
       |            <ans2:batchSize>$batchSize</ans2:batchSize>
       |            <ans2:batchCount>$batchCount</ans2:batchCount>
       |            <ans2:checksum>$checksum</ans2:checksum>
       |            <ans2:checksumAlgorithm>SHA-256</ans2:checksumAlgorithm>
       |            <ans2:fileSize>$fileSize</ans2:fileSize>
       |            <ans2:compressed>false</ans2:compressed>
       |            <ans2:properties>
       |                <ans2:property>
       |                    <ans2:name>CaseReference</ans2:name>
       |                    <ans2:value>$caseReference</ans2:value>
       |                </ans2:property>
       |                <ans2:property>
       |                    <ans2:name>Eori</ans2:name>
       |                    <ans2:value>$eori</ans2:value>
       |                </ans2:property>
       |                <ans2:property>
       |                    <ans2:name>DeclarationId</ans2:name>
       |                    <ans2:value>$declarationId</ans2:value>
       |                </ans2:property>
       |                <ans2:property>
       |                    <ans2:name>DeclarationType</ans2:name>
       |                    <ans2:value>$declarationType</ans2:value>
       |                </ans2:property>
       |                <ans2:property>
       |                    <ans2:name>ApplicationName</ans2:name>
       |                    <ans2:value>$applicationName</ans2:value>
       |                </ans2:property>
       |                <ans2:property>
       |                    <ans2:name>DocumentType</ans2:name>
       |                    <ans2:value>$documentType</ans2:value>
       |                </ans2:property>
       |                <ans2:property>
       |                    <ans2:name>DocumentReceivedDate</ans2:name>
       |                    <ans2:value>$documentReceivedDate</ans2:value>
       |                </ans2:property>
       |            </ans2:properties>
       |            <ans2:sourceLocation>$sourceLocation</ans2:sourceLocation>
       |            <ans2:sourceFileName>$sourceFileName</ans2:sourceFileName>
       |            <ans2:sourceFileMimeType>$sourceFileMimeType</ans2:sourceFileMimeType>
       |            <ans2:destinations>
       |                <ans2:destination>
       |                    <ans2:destinationSystem>$destinationSystem</ans2:destinationSystem>
       |                </ans2:destination>
       |            </ans2:destinations>
       |        </ans2:BatchFileInterfaceMetadata>
       |    </ans1:Body>
       |</ans1:Envelope>
       |""".stripMargin
      .filter(_ >= ' ')
      .replaceAll(">[\\s\r\n]*<", "><")

  def mockCcsSubmissionRequestGet()(
    response: Either[Error, Option[WorkItem[CcsSubmissionRequest]]]
  ): CallHandler0[EitherT[Future, models.Error, Option[WorkItem[CcsSubmissionRequest]]]] =
    (mockCcsSubmissionRepo.get _)
      .expects()
      .returning(EitherT.fromEither[Future](response))

  def mockCcsSubmissionRequestSet(
    ccsSubmissionRequest: CcsSubmissionRequest
  )(
    response: Either[Error, WorkItem[CcsSubmissionRequest]]
  ): CallHandler1[CcsSubmissionRequest, EitherT[Future, models.Error, WorkItem[CcsSubmissionRequest]]] =
    (mockCcsSubmissionRepo
      .set(_: CcsSubmissionRequest))
      .expects(ccsSubmissionRequest)
      .returning(EitherT.fromEither[Future](response))

  def mockSetProcessingStatus(id: BSONObjectID, status: ProcessingStatus)(
    response: Either[Error, Boolean]
  ): CallHandler2[BSONObjectID, ProcessingStatus, EitherT[Future, models.Error, Boolean]] =
    (mockCcsSubmissionRepo
      .setProcessingStatus(_: BSONObjectID, _: ProcessingStatus))
      .expects(id, status)
      .returning(EitherT.fromEither[Future](response))

  def mockSetResultStatus(id: BSONObjectID, status: ResultStatus)(
    response: Either[Error, Boolean]
  ): CallHandler2[BSONObjectID, ResultStatus, EitherT[Future, models.Error, Boolean]] =
    (mockCcsSubmissionRepo
      .setResultStatus(_: BSONObjectID, _: ResultStatus))
      .expects(id, status)
      .returning(EitherT.fromEither[Future](response))

  def mockDec64Submission(ccsSubmissionPayload: CcsSubmissionPayload)(
    response: Either[Error, HttpResponse]
  ): CallHandler2[CcsSubmissionPayload, HeaderCarrier, EitherT[Future, Error, HttpResponse]] =
    (mockCcsConnector
      .submitToCcs(_: CcsSubmissionPayload)(_: HeaderCarrier))
      .expects(ccsSubmissionPayload, *)
      .returning(EitherT[Future, Error, HttpResponse](Future.successful(response)))

  val actorSystem: ActorSystem                                                          = ActorSystem()
  implicit val ccsSubmissionPollerExecutionContext: CcsSubmissionPollerExecutionContext =
    new CcsSubmissionPollerExecutionContext(actorSystem)

  val ccsSubmissionService =
    new DefaultCcsSubmissionService(mockCcsConnector, mockCcsSubmissionRepo)

  "Ccs Submission Service" when {

    "the submission poller requests a work item" must {
      "dequeue the next work item" in {
        val workItem = sample[WorkItem[CcsSubmissionRequest]]
        mockCcsSubmissionRequestGet()(Right(Some(workItem)))
        await(ccsSubmissionService.dequeue.value) shouldBe Right(Some(workItem))
      }
    }

    "a ccs submission request is made" must {
      "enqueue the request" in {
        val supportingEvidence               = sample[SupportingEvidence]
        val completeSupportingEvidenceAnswer =
          sample[CompleteSupportingEvidenceAnswer].copy(evidences = List(supportingEvidence))
        val completeClaim                    = sample[CompleteC285Claim].copy(supportingEvidenceAnswers = completeSupportingEvidenceAnswer)
        val ccsSubmissionRequest             = sample[CcsSubmissionRequest]
        val workItem                         = sample[WorkItem[CcsSubmissionRequest]]
        val submitClaimRequest               = sample[SubmitClaimRequest].copy(completeClaim = completeClaim)
        val submitClaimResponse              = sample[SubmitClaimResponse]
        val maybeSupportingEvidence          = submitClaimRequest.completeClaim.evidences.headOption

        (maybeSupportingEvidence, submitClaimRequest.completeClaim.referenceNumberType) match {
          case (Some(evidence), value) =>
            val dec64payload = value match {
              case Left(value) =>
                makeDec64XmlPayload(
                  correlationId = submitClaimRequest.completeClaim.correlationId,
                  batchId = submitClaimRequest.completeClaim.correlationId,
                  batchSize = submitClaimRequest.completeClaim.evidences.size.toLong,
                  batchCount = 1L,
                  checksum = evidence.upscanSuccess.uploadDetails.checksum,
                  fileSize = evidence.upscanSuccess.uploadDetails.size,
                  caseReference = submitClaimResponse.caseNumber,
                  eori = submitClaimRequest.signedInUserDetails.eori.value,
                  declarationId = value.value,
                  declarationType = submitClaimRequest.completeClaim.declarantTypeAnswer.declarantType.toString,
                  applicationName = "NDRC",
                  documentType = evidence.documentType.map(s => s.toString).getOrElse(""),
                  documentReceivedDate = TimeUtils.cdsDateTimeFormat.format(evidence.uploadedOn),
                  sourceLocation = evidence.upscanSuccess.downloadUrl,
                  sourceFileName = evidence.upscanSuccess.uploadDetails.fileName,
                  sourceFileMimeType = evidence.upscanSuccess.uploadDetails.fileMimeType,
                  destinationSystem = "CDFPay"
                )

              case Right(value) =>
                makeDec64XmlPayload(
                  correlationId = submitClaimRequest.completeClaim.correlationId,
                  batchId = submitClaimRequest.completeClaim.correlationId,
                  batchSize = submitClaimRequest.completeClaim.evidences.size.toLong,
                  batchCount = 1L,
                  checksum = evidence.upscanSuccess.uploadDetails.checksum,
                  fileSize = evidence.upscanSuccess.uploadDetails.size,
                  caseReference = submitClaimResponse.caseNumber,
                  eori = submitClaimRequest.signedInUserDetails.eori.value,
                  declarationId = value.value,
                  declarationType = submitClaimRequest.completeClaim.declarantTypeAnswer.declarantType.toString,
                  applicationName = "NDRC",
                  documentType = evidence.documentType.map(s => s.toString).getOrElse(""),
                  documentReceivedDate = TimeUtils.cdsDateTimeFormat.format(evidence.uploadedOn),
                  sourceLocation = evidence.upscanSuccess.downloadUrl,
                  sourceFileName = evidence.upscanSuccess.uploadDetails.fileName,
                  sourceFileMimeType = evidence.upscanSuccess.uploadDetails.fileMimeType,
                  destinationSystem = "CDFPay"
                )

            }
            val updateWorkItem =
              workItem.copy(item = ccsSubmissionRequest.copy(payload = dec64payload, headers = hc.headers))

            mockCcsSubmissionRequestSet(ccsSubmissionRequest.copy(payload = dec64payload, headers = hc.headers))(
              Right(updateWorkItem)
            )

            await(ccsSubmissionService.enqueue(submitClaimRequest, submitClaimResponse) value) === Right(
              List(updateWorkItem)
            )

          case _ => fail
        }
      }
    }

    "the submission poller updates the processing status" must {
      "return true to indicate that the status has been updated" in {
        val workItem = sample[WorkItem[CcsSubmissionRequest]]
        mockSetProcessingStatus(workItem.id, Failed)(Right(true))
        await(ccsSubmissionService.setProcessingStatus(workItem.id, Failed).value) shouldBe Right(true)
      }
    }

    "the submission poller updates the complete status" must {
      "return true to indicate that the status has been updated" in {
        val workItem = sample[WorkItem[CcsSubmissionRequest]]
        mockSetResultStatus(workItem.id, PermanentlyFailed)(Right(true))
        await(ccsSubmissionService.setResultStatus(workItem.id, PermanentlyFailed).value) shouldBe Right(true)
      }
    }

    "a ccs file submission request is made" must {

      "return an error" when {

        "there is an issue with the DEC-64 service" in {
          val ccsSubmissionPayload = sample[CcsSubmissionPayload]

          inSequence {
            mockDec64Submission(ccsSubmissionPayload.copy(headers = hc.headers))(Left(Error("dec-64 service error")))
          }

          await(
            ccsSubmissionService
              .submitToCcs(ccsSubmissionPayload)
              .value
          ).isLeft shouldBe true
        }

      }

      "return a http no content success status when the file has been successfully submitted to the DEC-64 service" in {
        val ccsSubmissionPayload = sample[CcsSubmissionPayload]
        val response             = HttpResponse(204, "Success")
        inSequence {
          mockDec64Submission(ccsSubmissionPayload.copy(headers = hc.headers))(Right(response))
        }
        await(
          ccsSubmissionService
            .submitToCcs(
              ccsSubmissionPayload
            )
            .value
        ) shouldBe Right(
          response
        )
      }

    }
  }

}
