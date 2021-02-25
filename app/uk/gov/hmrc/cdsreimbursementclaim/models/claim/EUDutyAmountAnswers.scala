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

package uk.gov.hmrc.cdsreimbursementclaim.models.claim

import julienrf.json.derived
import play.api.libs.json.OFormat

sealed trait EUDutyAmountAnswers extends Product with Serializable

object EUDutyAmountAnswers {

  final case class IncompleteEUDutyAmountAnswer(
    euDutyAmounts: Option[EnterEuClaim]
  ) extends EUDutyAmountAnswers

  object IncompleteEUDutyAmountAnswer {
    val empty: IncompleteEUDutyAmountAnswer = IncompleteEUDutyAmountAnswer(None)

    implicit val format: OFormat[IncompleteEUDutyAmountAnswer] =
      derived.oformat[IncompleteEUDutyAmountAnswer]()
  }

  final case class CompleteEUDutyAmountAnswer(
    euDutyAmounts: EnterEuClaim
  ) extends EUDutyAmountAnswers

  object CompleteEUDutyAmountAnswer {
    implicit val format: OFormat[CompleteEUDutyAmountAnswer] =
      derived.oformat[CompleteEUDutyAmountAnswer]()
  }

  implicit val format: OFormat[EUDutyAmountAnswers] =
    derived.oformat[EUDutyAmountAnswers]()
}
