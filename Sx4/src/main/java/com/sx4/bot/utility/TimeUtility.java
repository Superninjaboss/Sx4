package com.sx4.bot.utility;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class TimeUtility {

	public static class OffsetTimeZone {

		private final TimeZone timeZone;
		private final long offset;

		private OffsetTimeZone(TimeZone timeZone, long offset) {
			this.timeZone = timeZone;
			this.offset = offset;
		}

		public TimeZone getTimeZone() {
			return this.timeZone;
		}

		public ZoneOffset asZoneOffset() {
			return ZoneOffset.ofTotalSeconds((int) this.getTotalOffset());
		}

		public long getOffset() {
			return this.offset;
		}

		public long getTotalOffset() {
			return this.timeZone.getRawOffset() + this.offset;
		}

		public boolean isNegative() {
			return this.offset < 0;
		}

		public String toString() {
			return this.timeZone.getID() + (this.isNegative() ? "-" : "+") + NumberUtility.getZeroPrefixedNumber(Math.abs(this.offset / 3600)) + ":" + NumberUtility.getZeroPrefixedNumber(Math.abs((this.offset % 3600) / 60));
		}

		public static OffsetTimeZone getTimeZone(String query) {
			char[] characters = query.toCharArray();

			int unitIndex = -1;
			for (int i = 0; i < characters.length; i++) {
				char character = characters[i];
				if (character == '-') {
					unitIndex = i;
				} else if (character == '+') {
					unitIndex = i;
				}
			}

			if (unitIndex == -1) {
				return new OffsetTimeZone(TimeZone.getTimeZone(query), 0L);
			}

			String offset = query.substring(unitIndex);

			int colonIndex = offset.indexOf(':');

			long offsetSeconds = 0L;
			try {
				if (colonIndex == -1) {
					offsetSeconds += offset.length() == 1 ? 0 : Integer.parseInt(offset) * 3600L;
				} else {
					String hourOffsetString = offset.substring(0, colonIndex);
					if (hourOffsetString.length() != 1) {
						offsetSeconds += Integer.parseInt(hourOffsetString) * 3600L;
						int minuteOffset = Integer.parseInt(offset.substring(colonIndex + 1));
						offsetSeconds += (offset.charAt(0) == '-' ? -minuteOffset : minuteOffset) * 60L;
					}
				}
			} catch (NumberFormatException ignored) {}

			return new OffsetTimeZone(TimeZone.getTimeZone(query.substring(0, unitIndex - 1)), offsetSeconds);
		}

	}

	public static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("dd LLL uuuu HH:mm");
	
	private static final List<String> SECONDS = List.of("s", "sec", "secs", "second", "seconds");
	private static final List<String> MINUTES = List.of("m", "min", "mins", "minute", "minutes");
	private static final List<String> HOURS = List.of("h", "hour", "hours");
	private static final List<String> DAYS = List.of("d", "day", "days");
	
	private static final ChronoUnit[] CHRONO_UNITS = {ChronoUnit.CENTURIES, ChronoUnit.DECADES, ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.WEEKS, ChronoUnit.DAYS, ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS};
	private static final ChronoUnit[] MUSIC_CHRONO_UNITS = {ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS};

	public static final Map<ChronoUnit, Map<Boolean, String>> LONG_SUFFIXES = new HashMap<>();
	public static final Map<ChronoUnit, Map<Boolean, String>> SHORT_SUFFIXES = new HashMap<>();
	static {
		for (ChronoUnit unit : TimeUtility.CHRONO_UNITS) {
			String unitName = unit.name().toLowerCase();
			switch (unit) {
				case CENTURIES -> LONG_SUFFIXES.compute(unit, (key, value) -> {
					Map<Boolean, String> map = value == null ? new HashMap<>() : value;
					map.put(true, " centuries");
					map.put(false, " century");
					return map;
				});
				case MILLENNIA -> LONG_SUFFIXES.compute(unit, (key, value) -> {
					Map<Boolean, String> map = value == null ? new HashMap<>() : value;
					map.put(true, " millennia");
					map.put(false, " millennium");
					return map;
				});
				default -> {
					String name = unitName.substring(0, unitName.length() - 1);
					LONG_SUFFIXES.compute(unit, (key, value) -> {
						Map<Boolean, String> map = value == null ? new HashMap<>() : value;
						map.put(true, " " + name + "s");
						map.put(false, " " + name);
						return map;
					});
				}
			}

			String suffix = unit == ChronoUnit.MONTHS ? "M" : unitName.substring(0, 1);
			SHORT_SUFFIXES.compute(unit, (key, value) -> {
				Map<Boolean, String> map = value == null ? new HashMap<>() : value;
				map.put(true, suffix);
				map.put(false, suffix);
				return map;
			});
		}
	}
	
	private static LocalDate parseDate(String date, String character, int currentYear) {
		String[] dateSplit = date.split(character);
		
		String yearString = null;
		if (dateSplit.length == 3) {
			yearString = dateSplit[2];
		}
		
		String dayString = dateSplit[0], monthString = dateSplit[1];
		
		if (!NumberUtility.isNumberUnsigned(dayString) || !NumberUtility.isNumberUnsigned(monthString)) {
			return null;
		}
		
		int day = Integer.parseInt(dayString), month = Integer.parseInt(monthString), year;
		if (yearString != null && NumberUtility.isNumberUnsigned(yearString)) {
			year = Integer.parseInt(yearString);
			
			if (yearString.length() == 2) {
				int remainder = currentYear % 100;
				year = currentYear - remainder + year;
			}
		} else {
			year = currentYear;
		}
		
		return LocalDate.of(year, month, day);
	}
	
	public static Duration getDurationFromDateTime(String dateTime) {
		return TimeUtility.getDurationFromDateTime(dateTime, "GMT");
	}
	
	public static Duration getDurationFromDateTime(String dateTime, String defaultTimeZone) {
		int lastSpace = dateTime.lastIndexOf(' ');

		OffsetDateTime now;
		OffsetTimeZone timeZone = OffsetTimeZone.getTimeZone(dateTime.substring(lastSpace + 1));
		if (timeZone.getTotalOffset() != 0) {
			dateTime = dateTime.substring(0, lastSpace);
		} else {
			timeZone = OffsetTimeZone.getTimeZone(defaultTimeZone);
		}

		now = OffsetDateTime.now(timeZone.getTimeZone().toZoneId()).plusSeconds(timeZone.getOffset());

		String[] dateTimeSplit = dateTime.split(" ");

		boolean dateGiven = false;
		int day = now.getDayOfMonth(), month = now.getMonthValue(), year = now.getYear(), hour = 0, minute = 0;
		for (String part : dateTimeSplit) {
			if (part.contains(":")) {
				String[] partSplit = part.split(":");
				String hourString = partSplit[0], minuteString = partSplit[1];
				
				if (!NumberUtility.isNumberUnsigned(hourString) || !NumberUtility.isNumberUnsigned(minuteString)) {
					continue;
				}
				
				int hourInt = Integer.parseInt(hourString);
				if (hourInt <= 24 && hourInt >= 0) {
					hour = hourInt == 24 ? 0 : hourInt;
				}
				
				int minuteInt = Integer.parseInt(minuteString);
				if (minuteInt <= 59 && minuteInt >= 0) {
					minute = minuteInt;
				}

				continue;
			} else if (part.contains("/")) {
				LocalDate date = TimeUtility.parseDate(part, "/", now.getYear());
				if (date == null) {
					continue;
				}

				year = date.getYear();
				month = date.getMonthValue();
				day = date.getDayOfMonth();
			} else if (part.contains("-")) {
				LocalDate date = TimeUtility.parseDate(part, "-", now.getYear());
				if (date == null) {
					continue;
				}

				year = date.getYear();
				month = date.getMonthValue();
				day = date.getDayOfMonth();
			}

			dateGiven = true;
		}

		OffsetDateTime time = OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
		if (!dateGiven && time.isBefore(now)) {
			time = time.plusDays(1);
		}

		return Duration.between(now, time);
	}

	public static String getTimeString(long duration, TimeUnit unit, Map<ChronoUnit, Map<Boolean, String>> units, int maxUnits) {
		long seconds = unit.toSeconds(duration);

		StringBuilder string = new StringBuilder();
		int unitsUsed = 0;
		for (int i = 0; i < TimeUtility.CHRONO_UNITS.length; i++) {
			ChronoUnit chronoUnit = TimeUtility.CHRONO_UNITS[i];

			long secondsInTime = chronoUnit.getDuration().getSeconds();
			int amount = (int) Math.floor((double) seconds / secondsInTime);

			if (amount != 0) {
				Map<Boolean, String> suffix = units.get(chronoUnit);
				if (suffix != null) {
					string.append(amount).append(suffix.get(amount != 1));

					if (++unitsUsed == maxUnits) {
						return string.toString();
					}

					seconds -= amount * secondsInTime;
				}

				if (seconds == 0) {
					break;
				}

				if (i != TimeUtility.CHRONO_UNITS.length - 1) {
					string.append(" ");
				}
			}
		}

		return string.toString();
	}

	public static String getTimeString(long seconds, Map<ChronoUnit, Map<Boolean, String>> units, int maxUnits) {
		return TimeUtility.getTimeString(seconds, TimeUnit.SECONDS, units, maxUnits);
	}

	public static String getTimeString(long seconds, Map<ChronoUnit, Map<Boolean, String>> units) {
		return TimeUtility.getTimeString(seconds, units, -1);
	}

	public static String getTimeString(long seconds, TimeUnit unit) {
		return TimeUtility.getTimeString(seconds, unit, TimeUtility.LONG_SUFFIXES, -1);
	}
	
	public static String getTimeString(long seconds) {
		return TimeUtility.getTimeString(seconds, TimeUnit.SECONDS);
	}

	public static String getMusicTimeString(long seconds) {
		return TimeUtility.getMusicTimeString(seconds, TimeUnit.SECONDS);
	}

	public static String getMusicTimeString(long duration, TimeUnit unit) {
		long seconds = unit.toSeconds(duration);

		StringBuilder string = new StringBuilder();
		
		boolean overZero = false;
		for (int i = 0; i < TimeUtility.MUSIC_CHRONO_UNITS.length; i++) {
			ChronoUnit chronoUnit = TimeUtility.MUSIC_CHRONO_UNITS[i];
			
			long secondsInTime = chronoUnit.getDuration().getSeconds();
			int amount = (int) Math.floor((double) seconds / secondsInTime);
			
			if (!overZero) {
				overZero = amount != 0;
			} 
			
			if (overZero) {
				string.append(amount >= 0 && amount < 10 ? "0" : "").append(amount).append(i == TimeUtility.MUSIC_CHRONO_UNITS.length - 1 ? "" : ":");
				
				seconds -= amount * secondsInTime;
			}
		}
		
		return string.toString();
	}
	
	private static long getActualSeconds(long time, String suffix) {
		if (TimeUtility.SECONDS.contains(suffix)) {
			return time;
		} else if (TimeUtility.MINUTES.contains(suffix)) {
			return time * 60L;
		} else if (TimeUtility.HOURS.contains(suffix)) {
			return time * 3600L;
		} else if (TimeUtility.DAYS.contains(suffix)) {
			return time * 86400L;
		}
		
		return 0;
	}
	
	public static Duration getDurationFromString(String query) {
		char[] charArray = query.toCharArray();
		
		StringBuilder numberReader = new StringBuilder();
		StringBuilder unitReader = new StringBuilder();
		
		long seconds = 0;
		for (int i = 0; i < charArray.length; i++) {
			char character = charArray[i];
			
			if (Character.isDigit(character)) {
				if (unitReader.length() == 0) {
					numberReader.append(character);
				} else {
					long time = Long.parseLong(numberReader.toString());
					seconds += TimeUtility.getActualSeconds(time, unitReader.toString());
					
					numberReader = new StringBuilder(String.valueOf(character));
					unitReader = new StringBuilder();
				}
			} else if (!Character.isWhitespace(character)) {
				if (i == 0) {
					return null;
				}
				
				unitReader.append(Character.toLowerCase(character));
				
				if (i == charArray.length - 1) {
					long time = numberReader.length() == 0 ? 0 : Long.parseLong(numberReader.toString());
					seconds += TimeUtility.getActualSeconds(time, unitReader.toString());
				}
			}
		}
		
		return Duration.of(seconds, ChronoUnit.SECONDS);
	}
	
}
