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

package uk.gov.hmrc.cdsreimbursementclaim.services
import cats.data.EitherT
import cats.instances.future._
import cats.instances.int._
import cats.syntax.either._
import cats.syntax.eq._
import com.google.inject.ImplementedBy
import play.api.http.Status.OK
import play.api.libs.json.Json
import play.api.mvc.Request
import uk.gov.hmrc.cdsreimbursementclaim.connectors.ClaimConnector
import uk.gov.hmrc.cdsreimbursementclaim.metrics.Metrics
import uk.gov.hmrc.cdsreimbursementclaim.models.Error
import uk.gov.hmrc.cdsreimbursementclaim.models.claim.audit.{SubmitClaimEvent, SubmitClaimResponseEvent}
import uk.gov.hmrc.cdsreimbursementclaim.models.claim.{SubmitClaimRequest, SubmitClaimResponse}
import uk.gov.hmrc.cdsreimbursementclaim.models.eis.claim.{EisSubmitClaimRequest, EisSubmitClaimResponse}
import uk.gov.hmrc.cdsreimbursementclaim.models.email.EmailRequest
import uk.gov.hmrc.cdsreimbursementclaim.services.audit.AuditService
import uk.gov.hmrc.cdsreimbursementclaim.utils.HttpResponseOps.HttpResponseOps
import uk.gov.hmrc.cdsreimbursementclaim.utils.Logging
import uk.gov.hmrc.cdsreimbursementclaim.utils.Logging._
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@ImplementedBy(classOf[DefaultClaimService])
trait ClaimService {
  def submitClaim(submitClaimRequest: SubmitClaimRequest)(implicit
    hc: HeaderCarrier,
    request: Request[_]
  ): EitherT[Future, Error, SubmitClaimResponse]
}

@Singleton
class DefaultClaimService @Inject() (
  claimConnector: ClaimConnector,
  claimTransformerService: ClaimTransformerService,
  emailService: EmailService,
  auditService: AuditService,
  metrics: Metrics
)(implicit
  ec: ExecutionContext
) extends ClaimService
    with Logging {

  def submitClaim(
    submitClaimRequest: SubmitClaimRequest
  )(implicit hc: HeaderCarrier, request: Request[_]): EitherT[Future, Error, SubmitClaimResponse] = {

    val maybeEisSubmitClaimRequest: Either[Error, EisSubmitClaimRequest] =
      claimTransformerService.toEisSubmitClaimRequest(submitClaimRequest)

    val emailRequest: EmailRequest = EmailRequest(
      submitClaimRequest.signedInUserDetails.verifiedEmail,
      submitClaimRequest.signedInUserDetails.contactName,
      submitClaimRequest.completeClaim.claims.total
    )

    for {
      eisSubmitRequest       <-
        EitherT.fromEither[Future](maybeEisSubmitClaimRequest).leftMap(e => Error(s"could not make TPIO5 payload: $e"))
      _                      <- auditClaimBeforeSubmit(submitClaimRequest, eisSubmitRequest)
      returnHttpResponse     <- submitClaimAndAudit(submitClaimRequest, eisSubmitRequest)
      eisSubmitClaimResponse <- EitherT.fromEither[Future](
                                  returnHttpResponse.parseJSON[EisSubmitClaimResponse]().leftMap(Error(_))
                                )
      claimResponse          <- prepareSubmitClaimResponse(eisSubmitClaimResponse)
      _                      <- emailService.sendClaimConfirmationEmail(emailRequest, claimResponse).leftFlatMap { e =>
                                  logger.warn("could not send claim submission confirmation email or audit event", e)
                                  EitherT.pure[Future, Error](())
                                }
    } yield claimResponse

  }

  private def auditClaimBeforeSubmit(
    submitClaimRequest: SubmitClaimRequest,
    eisSubmitClaimRequest: EisSubmitClaimRequest
  )(implicit hc: HeaderCarrier, request: Request[_]): EitherT[Future, Error, Unit] =
    EitherT.pure[Future, Error](
      auditService.sendEvent(
        "SubmitClaim",
        SubmitClaimEvent(
          eisSubmitClaimRequest,
          submitClaimRequest.signedInUserDetails.eori
        ),
        "submit-claim"
      )
    )

  private def submitClaimAndAudit(
    submitClaimRequest: SubmitClaimRequest,
    eisSubmitClaimRequest: EisSubmitClaimRequest
  )(implicit hc: HeaderCarrier, request: Request[_]): EitherT[Future, Error, HttpResponse] = {

    val timer = metrics.submitClaimTimer.time()
    claimConnector
      .submitClaim(
        eisSubmitClaimRequest
      )
      .subflatMap { httpResponse =>
        timer.close()
        auditSubmitClaimResponse(
          httpResponse.status,
          httpResponse.body,
          submitClaimRequest,
          eisSubmitClaimRequest
        )

        if (httpResponse.status === OK)
          Right(httpResponse)
        else {
          metrics.submitClaimErrorCounter.inc()
          Left(Error(s"call to submit claim came back with status ${httpResponse.status}"))
        }
      }
  }

  private def auditSubmitClaimResponse(
    responseHttpStatus: Int,
    responseBody: String,
    submitClaimRequest: SubmitClaimRequest,
    eisSubmitClaimRequest: EisSubmitClaimRequest
  )(implicit hc: HeaderCarrier, request: Request[_]): Unit = {
    val responseJson =
      Try(Json.parse(responseBody))
        .getOrElse(Json.parse(s"""{ "body" : "could not parse body as JSON: $responseBody" }"""))
    val requestJson  = Json.toJson(eisSubmitClaimRequest)
    auditService.sendEvent(
      "SubmitClaimResponse",
      SubmitClaimResponseEvent(
        responseHttpStatus,
        responseJson,
        requestJson,
        submitClaimRequest
      ),
      "submit-claim-response"
    )
  }

  private def prepareSubmitClaimResponse(
    response: EisSubmitClaimResponse
  ): EitherT[Future, Error, SubmitClaimResponse] =
    EitherT.fromEither[Future] {
      response.postNewClaimsResponse.responseCommon.errorMessage match {
        case Some(error) =>
          Left(
            Error(
              s"""submission of claim failed : $error | ${response.postNewClaimsResponse.responseCommon.returnParameters
                .map(e => e.mkString("; "))}"""
            )
          )
        case None        =>
          response.postNewClaimsResponse.responseCommon.CDFPayCaseNumber match {
            case Some(caseNumber) => Right(SubmitClaimResponse(caseNumber))
            case None             => Left(Error("No case number returned in response"))
          }
      }
    }
}
