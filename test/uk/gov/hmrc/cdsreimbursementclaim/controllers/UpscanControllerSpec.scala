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

package uk.gov.hmrc.cdsreimbursementclaim.controllers

import akka.stream.Materializer
import cats.data.EitherT
import org.scalamock.handlers.{CallHandler1, CallHandler2}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.inject.bind
import play.api.inject.guice.GuiceableModule
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{BodyParsers, Headers, WrappedRequest}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers, NoMaterializer}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.cdsreimbursementclaim.Fake
import uk.gov.hmrc.cdsreimbursementclaim.controllers.actions.{AuthenticateActionBuilder, AuthenticateActions, AuthenticatedRequest}
import uk.gov.hmrc.cdsreimbursementclaim.models.Error
import uk.gov.hmrc.cdsreimbursementclaim.models.generators.Generators.sample
import uk.gov.hmrc.cdsreimbursementclaim.models.generators.UpscanGen._
import uk.gov.hmrc.cdsreimbursementclaim.models.upscan.UpscanCallBack.{UploadDetails, UpscanSuccess}
import uk.gov.hmrc.cdsreimbursementclaim.models.upscan._
import uk.gov.hmrc.cdsreimbursementclaim.services.UpscanService
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{LocalDate, LocalDateTime, ZoneId}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class UpscanControllerSpec extends ControllerSpec with ScalaCheckDrivenPropertyChecks {

  val mockUpscanService: UpscanService = mock[UpscanService]
  val fixedTimestamp: LocalDateTime    = LocalDateTime.of(2019, 9, 24, 15, 47, 20)

  val executionContext: ExecutionContextExecutor = ExecutionContext.global

  val mockAuthConnector: AuthConnector = mock[AuthConnector]

  val builder = new AuthenticateActionBuilder(
    mockAuthConnector,
    new BodyParsers.Default()(NoMaterializer),
    executionContext
  )

  override val overrideBindings: List[GuiceableModule] =
    List(
      bind[AuthConnector].toInstance(mockAuthConnector),
      bind[AuthenticateActions].toInstance(Fake.login(Fake.user, fixedTimestamp)),
      bind[UpscanService].toInstance(mockUpscanService)
    )

  implicit lazy val mat: Materializer = fakeApplication.materializer

  val headerCarrier: HeaderCarrier = HeaderCarrier()

  def mockStoreUpscanUpload(upscanUpload: UpscanUpload)(
    response: Either[Error, Unit]
  ): CallHandler1[UpscanUpload, EitherT[Future, Error, Unit]] =
    (mockUpscanService
      .storeUpscanUpload(_: UpscanUpload))
      .expects(upscanUpload)
      .returning(EitherT[Future, Error, Unit](Future.successful(response)))

  def mockUpdateUpscanUpload(
    uploadReference: UploadReference,
    upscanUpload: UpscanUpload
  )(
    response: Either[Error, Unit]
  ): CallHandler2[UploadReference, UpscanUpload, EitherT[Future, Error, Unit]] =
    (mockUpscanService
      .updateUpscanUpload(_: UploadReference, _: UpscanUpload))
      .expects(uploadReference, upscanUpload)
      .returning(EitherT[Future, Error, Unit](Future.successful(response)))

  def mockGetUpscanUpload(uploadReference: UploadReference)(
    response: Either[Error, Option[UpscanUpload]]
  ): CallHandler1[UploadReference, EitherT[Future, Error, Option[UpscanUpload]]] =
    (mockUpscanService
      .readUpscanUpload(_: UploadReference))
      .expects(uploadReference)
      .returning(EitherT[Future, Error, Option[UpscanUpload]](Future.successful(response)))

  def mockGetUpscanUploads(uploadReferences: List[UploadReference])(
    response: Either[Error, List[UpscanUpload]]
  ): CallHandler1[List[UploadReference], EitherT[Future, Error, List[UpscanUpload]]] =
    (mockUpscanService
      .readUpscanUploads(_: List[UploadReference]))
      .expects(uploadReferences)
      .returning(EitherT[Future, Error, List[UpscanUpload]](Future.successful(response)))

  val request = new AuthenticatedRequest(
    Fake.user,
    LocalDateTime.now(),
    headerCarrier,
    FakeRequest()
  )

  def fakeRequestWithJsonBody(body: JsValue): WrappedRequest[JsValue] =
    request.withHeaders(Headers.apply(CONTENT_TYPE -> JSON)).withBody(body)

  val controller                                                      = new UpscanController(
    authenticate = Fake.login(Fake.user, LocalDateTime.of(2020, 1, 1, 15, 47, 20)),
    upscanService = mockUpscanService,
    cc = Helpers.stubControllerComponents()
  )

  val uploadReference: UploadReference = sample[UploadReference]

  "Upscan Controller" when {

    "it receives a request to get an upscan upload" must {

      "return an internal server error if the backend call fails" in {
        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            FakeRequest()
          )

        mockGetUpscanUpload(uploadReference)(Left(Error("mongo error")))

        val result = controller.getUpscanUpload(uploadReference)(request)
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }

      "return a bad request if the store descriptor structure is corrupted" in {

        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            FakeRequest()
          )

        mockGetUpscanUpload(uploadReference)(Right(None))

        val result = controller.getUpscanUpload(uploadReference)(request)
        status(result) shouldBe BAD_REQUEST

      }

      "return a 200 OK if the backend call succeeds" in {

        val upscanUpload = sample[UpscanUpload]

        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            FakeRequest()
          )

        mockGetUpscanUpload(uploadReference)(Right(Some(upscanUpload)))

        val result = controller.getUpscanUpload(uploadReference)(request)
        status(result) shouldBe OK

      }

    }

    "it receives a request to get upscan uploads" must {

      "return an internal server error if the upload reference cannot be found" in {
        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            FakeRequest()
          ).withBody(Json.parse(s"""{ "uploadReferences" : [ ${Json.toJson(uploadReference)} ] }"""))

        mockGetUpscanUploads(List(uploadReference))(Left(Error("mongo error")))

        val result = controller.getUpscanUploads()(request)
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }

      "return an internal server error if the backend call fails" in {
        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            FakeRequest()
          ).withBody(Json.parse(s"""{ "uploadReferences" : [ ${Json.toJson(uploadReference)} ] }"""))

        mockGetUpscanUploads(List(uploadReference))(Left(Error("mongo error")))

        val result = controller.getUpscanUploads()(request)
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }

      "return a bad request if the JSON body cannot be parsed" in {
        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            FakeRequest()
          ).withBody(Json.parse("""{ "things" : "other things" }"""))

        val result = controller.getUpscanUploads()(request)
        status(result) shouldBe BAD_REQUEST
      }

      "return a 200 OK if the backend call succeeds" in {

        val upscanUpload = sample[UpscanUpload]

        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            FakeRequest()
          ).withBody(Json.parse(s"""{ "uploadReferences" : [ ${Json.toJson(uploadReference)} ] }"""))

        mockGetUpscanUploads(List(uploadReference))(Right(List(upscanUpload)))

        val result = controller.getUpscanUploads()(request)
        status(result) shouldBe OK

      }

    }

    "it receives a request to save an upscan upload" must {

      "return an internal server error if the backend call fails" in {

        val upscanUploadPayload =
          s"""
             |{
             |    "uploadReference" : "abc",
             |    "upscanUploadMeta" : {
             |        "reference" : "glwibAzzhpamXyavalyif",
             |        "uploadRequest" : {
             |            "href" : "wveovofmaobqq",
             |            "fields" : {}
             |        }
             |    },
             |    "uploadedOn" : "1970-01-01T01:00:07.665",
             |    "upscanUploadStatus" : {
             |        "Initiated" : {}
             |    }
             |}
             |""".stripMargin

        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            fakeRequestWithJsonBody(Json.parse(upscanUploadPayload))
          )

        val upscanUpload = Json.parse(upscanUploadPayload).as[UpscanUpload]

        mockStoreUpscanUpload(upscanUpload)(Left(Error("mongo error")))

        val result = controller.saveUpscanUpload()(request)
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }

      "return a bad request if the request payload is incorrect" in {

        val badUpscanUploadPayload =
          """
            |{
            |    "uploadReference" : "abc",
            |    "upscanUploadMeta" : {
            |        "reference" : "glwibAzzhpamXyavalyif",
            |        "uploadRequest" : {
            |            "href" : "wveovofmaobqq",
            |            "fields" : {}
            |        }
            |    },
            |    "upscanUploadStatus" : {
            |        "Initiated" : {}
            |    }
            |}
            |""".stripMargin

        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            fakeRequestWithJsonBody(Json.parse(badUpscanUploadPayload))
          )

        val result = controller.saveUpscanUpload()(request)
        status(result) shouldBe BAD_REQUEST
      }

      "return an 200 OK response if a valid request is received" in {

        val upscanUploadPayload =
          """
            |{
            |    "uploadReference" : "abc",
            |    "upscanUploadMeta" : {
            |        "reference" : "glwibAzzhpamXyavalyif",
            |        "uploadRequest" : {
            |            "href" : "wveovofmaobqq",
            |            "fields" : {}
            |        }
            |    },
            |    "uploadedOn" : "1970-01-01T01:00:07.665",
            |    "upscanUploadStatus" : {
            |        "Initiated" : {}
            |    }
            |}
            |""".stripMargin

        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            fakeRequestWithJsonBody(Json.parse(upscanUploadPayload))
          )

        val upscanUpload = Json.parse(upscanUploadPayload).as[UpscanUpload]

        mockStoreUpscanUpload(upscanUpload)(Right(()))

        val result = controller.saveUpscanUpload()(request)
        status(result) shouldBe OK
      }
    }

    "it receives a upscan call back request" must {

      "return an internal server error if there is no corresponding upload reference" in {
        val uploadReference = UploadReference("11370e18-6e24-453e-b45a-76d3e32ea33d")
        val upscanUpload    = sample[UpscanUpload].copy(uploadReference = uploadReference)

        val upscanCallBackRequest =
          s"""
             |{
             |    "reference" : "reference",
             |    "fileStatus" : "READY",
             |    "downloadUrl" : "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
             |    "uploadDetails": {
             |        "uploadTimestamp": "2018-04-24T09:30:00Z",
             |        "checksum": "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
             |        "fileName": "test.pdf",
             |        "fileMimeType": "application/pdf"
             |    }
             |}
             |""".stripMargin

        inSequence {
          mockGetUpscanUpload(upscanUpload.uploadReference)(Right(None))
        }

        val result = controller.callback(
          uploadReference
        )(fakeRequestWithJsonBody(Json.parse(upscanCallBackRequest)))
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }

      "return internal server error if the payload contains a corrupt upscan success payload structure" in {
        val uploadReference = UploadReference("11370e18-6e24-453e-b45a-76d3e32ea33d")
        val upscanUpload    = sample[UpscanUpload].copy(uploadReference = uploadReference)

        val upscanCallBackRequest =
          s"""
             |{
             |    "fileStatus" : "READY"
             |}
             |""".stripMargin

        inSequence {
          mockGetUpscanUpload(upscanUpload.uploadReference)(Right(Some(upscanUpload)))
        }

        val result = controller.callback(
          uploadReference
        )(fakeRequestWithJsonBody(Json.parse(upscanCallBackRequest)))
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }

      "return internal server error if the payload contains a corrupt upscan failure payload structure" in {
        val uploadReference = UploadReference("11370e18-6e24-453e-b45a-76d3e32ea33d")
        val upscanUpload    = sample[UpscanUpload].copy(uploadReference = uploadReference)

        val upscanCallBackRequest =
          s"""
             |{
             |    "fileStatus" : "FAILED"
             |}
             |""".stripMargin

        inSequence {
          mockGetUpscanUpload(upscanUpload.uploadReference)(Right(Some(upscanUpload)))
        }

        val result = controller.callback(
          uploadReference
        )(fakeRequestWithJsonBody(Json.parse(upscanCallBackRequest)))
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }

      "return an internal server error if the payload does not contain a upscan status" in {

        val upscanUploadPayload =
          """
            |{
            |    "uploadReference" : "abc",
            |    "upscanUploadMeta" : {
            |        "reference" : "glwibAzzhpamXyavalyif",
            |        "uploadRequest" : {
            |            "href" : "wveovofmaobqq",
            |            "fields" : {}
            |        }
            |    },
            |    "uploadedOn" : "1970-01-01T01:00:07.665",
            |    "upscanUploadStatus" : {
            |        "Initiated" : {}
            |    }
            |}
            |""".stripMargin

        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            fakeRequestWithJsonBody(Json.parse(upscanUploadPayload))
          )

        val result = controller.callback(
          sample[UploadReference]
        )(request)
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }

      "return an internal server error if the payload does not contain a valid status" in {

        val upscanUploadPayload =
          """
            |{
            |    "reference" : "11370e18-6e24-453e-b45a-76d3e32ea33d",
            |    "fileStatus" : "SOME BAD STATUS",
            |    "downloadUrl" : "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
            |    "uploadDetails": {
            |        "uploadTimestamp": "2018-04-24T09:30:00Z",
            |        "checksum": "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
            |        "fileName": "test.pdf",
            |        "fileMimeType": "application/pdf"
            |    }
            |}
            |""".stripMargin

        val request =
          new AuthenticatedRequest(
            Fake.user,
            LocalDateTime.now(),
            headerCarrier,
            fakeRequestWithJsonBody(Json.parse(upscanUploadPayload))
          )

        val result = controller.callback(
          sample[UploadReference]
        )(request)
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }

      "return NO CONTENT if the payload contains a valid status" in {
        val uploadReference = UploadReference("11370e18-6e24-453e-b45a-76d3e32ea33d")
        val upscanUpload    = sample[UpscanUpload].copy(uploadReference = uploadReference)
        val uploadDetails   = sample[UploadDetails].copy(
          size = 9,
          fileName = "test.pdf",
          fileMimeType = "application/pdf",
          checksum = "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
          uploadTimestamp = LocalDate.parse("2018-04-24").atStartOfDay(ZoneId.of("Europe/Paris")).toInstant
        )
        val upscanSuccess   = sample[UpscanSuccess].copy(
          reference = "reference",
          fileStatus = "READY",
          downloadUrl = "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
          uploadDetails = uploadDetails
        )

        val upscanCallBackRequest =
          s"""
             |{
             |    "reference" : "reference",
             |    "fileStatus" : "READY",
             |    "downloadUrl" : "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
             |    "uploadDetails": {
             |        "uploadTimestamp": "2018-04-23T22:00:00Z",
             |        "checksum": "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
             |        "fileName": "test.pdf",
             |        "fileMimeType": "application/pdf",
             |        "size": 9
             |    }
             |}
             |""".stripMargin

        inSequence {
          mockGetUpscanUpload(upscanUpload.uploadReference)(Right(Some(upscanUpload)))
          mockUpdateUpscanUpload(
            upscanUpload.uploadReference,
            upscanUpload.copy(upscanCallBack = Some(upscanSuccess))
          )(Right(()))
        }

        val result = controller.callback(
          uploadReference
        )(fakeRequestWithJsonBody(Json.parse(upscanCallBackRequest)))
        status(result) shouldBe NO_CONTENT
      }

    }

  }
}
