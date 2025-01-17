package com.sx4.bot.formatter.parser;

import com.sx4.bot.formatter.function.FormatterEvent;
import com.sx4.bot.formatter.function.FormatterFunction;

import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;

public class PlusSecondsFunction extends FormatterFunction<Temporal> {

	public PlusSecondsFunction() {
		super(Temporal.class, "plusSeconds", "Adds a certain amount of seconds to a date");
	}

	public Temporal parse(FormatterEvent<Temporal> event, Long amount) {
		return event.getObject().plus(amount, ChronoUnit.SECONDS);
	}

}
