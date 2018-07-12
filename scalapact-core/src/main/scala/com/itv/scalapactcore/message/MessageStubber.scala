package com.itv.scalapactcore.message

import com.itv.scalapact.shared.matchir.IrNodeMatchingRules
import com.itv.scalapact.shared.typeclasses.{IMessageFormat, IPactReader}
import com.itv.scalapact.shared.Message
import com.itv.scalapactcore.common.matching.MessageMatchers.OutcomeAndMessage
import com.itv.scalapactcore.common.matching.{MatchOutcome, MatchOutcomeFailed, MatchOutcomeSuccess, MessageMatchers}

object MessageStubber {

  case class Config(strictMode: Boolean)

  def apply[A](messages: List[Message], config: MessageStubber.Config)(implicit matchingRules: IrNodeMatchingRules,
                                                                       pactReader: IPactReader): IMessageStubber[A] =
    MessageStubber.apply[A](messages, config, MatchOutcomeSuccess, List.empty)

  def apply[A](
      messages: List[Message],
      config: MessageStubber.Config,
      outcomes: MatchOutcome,
      currentResults: List[A]
  )(implicit matchingRules: IrNodeMatchingRules, pactReader: IPactReader): IMessageStubber[A] =
    new IMessageStubber[A] {

      private def messageStub(outcome: MatchOutcome, result: Option[A]): IMessageStubber[A] = MessageStubber.apply(
        messages,
        config,
        outcomes + outcome,
        result.toList ++ results
      )

      private def success(result: A): IMessageStubber[A]          = messageStub(MatchOutcomeSuccess, Some(result))
      private def fail(outcome: MatchOutcome): IMessageStubber[A] = messageStub(outcomes + outcome, None)
      private def fail(outcome: String): IMessageStubber[A]       = messageStub(outcomes + MatchOutcomeFailed(outcome), None)
      private def none: IMessageStubber[A]                        = this

      private def noDescriptionFound(description: String) =
        fail(s"No description `$description` found in:\n [ ${messages.map(_.renderAsString).mkString("\n")} \n ]")

      def consume(description: String)(test: Message => A): IMessageStubber[A] =
        messages
          .find(_.description == description)
          .map(test)
          .fold(noDescriptionFound(description)) { r =>
            success(r)
          }

      def publish[T](description: String, actualMessage: T, metadata: Message.Metadata)(
          implicit messageFormat: IMessageFormat[T]
      ): IMessageStubber[A] =
        messages
          .find(m => m.description == description)
          .map(
            message =>
              OutcomeAndMessage(
                MessageMatchers.matchSingleMessage(message.contents,
                                                   messageFormat.encode(actualMessage),
                                                   Some(message.matchingRules),
                                                   config.strictMode),
                message
            )
          )
          .map {
            case OutcomeAndMessage(oc, message) if !message.metaData.forall(n => metadata.get(n._1).contains(n._2)) =>
              fail(oc + MatchOutcomeFailed(s"Metadata does not match: ${message.metaData} /= $metadata"))
            case OutcomeAndMessage(MatchOutcomeSuccess, _) => none
            case outcomeAndMessage @ OutcomeAndMessage(MatchOutcomeFailed(_, _), _) =>
              fail(
                MessageMatchers
                  .renderOutcome(Some(outcomeAndMessage), messageFormat.encode(actualMessage), description)
                  .fold(identity, message => s"It should not succeed for [$message]")
              )
          }
          .getOrElse(noDescriptionFound(description))

      override def results: List[A]      = currentResults
      override def outcome: MatchOutcome = outcomes
    }
}