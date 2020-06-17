package org.apache.hadoop.contrib.ftp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KerberosName {

	/** The first component of the name */
	private final String serviceName;
	/** The second component of the name. It may be null. */
	private final String hostName;
	/** The realm of the name. */
	private final String realm;

	private static String defaultRealm;

	private static final Logger LOG = LoggerFactory.getLogger(KerberosName.class);

	private static final Pattern nameParser = Pattern.compile("([^/@]+)(/([^/@]+))?(@([^/@]+))?");

	/**
	 * A pattern that matches a string with out '$' and then a single parameter with
	 * $n.
	 */
	private static Pattern parameterPattern = Pattern.compile("([^$]*)(\\$(\\d*))?");

	private static final Pattern nonSimplePattern = Pattern.compile("[/@]");

	/**
	 * A pattern for parsing a auth_to_local rule.
	 */
	private static final Pattern ruleParser = Pattern
			.compile("\\s*((DEFAULT)|(RULE:\\[(\\d*):([^\\]]*)](\\(([^)]*)\\))?" + "(s/([^/]*)/([^/]*)/(g)?)?))/?(L)?");

	/**
	 * The list of translation rules.
	 */
	private static List<Rule> rules;

	private static class Rule {
		private final boolean isDefault;
		private final int numOfComponents;
		private final String format;
		private final Pattern match;
		private final Pattern fromPattern;
		private final String toPattern;
		private final boolean repeat;
		private final boolean toLowerCase;

		Rule() {
			isDefault = true;
			numOfComponents = 0;
			format = null;
			match = null;
			fromPattern = null;
			toPattern = null;
			repeat = false;
			toLowerCase = false;
		}

		Rule(int numOfComponents, String format, String match, String fromPattern, String toPattern, boolean repeat,
				boolean toLowerCase) {
			isDefault = false;
			this.numOfComponents = numOfComponents;
			this.format = format;
			this.match = match == null ? null : Pattern.compile(match);
			this.fromPattern = fromPattern == null ? null : Pattern.compile(fromPattern);
			this.toPattern = toPattern;
			this.repeat = repeat;
			this.toLowerCase = toLowerCase;
		}

		@Override
		public String toString() {
			StringBuilder buf = new StringBuilder();
			if (isDefault) {
				buf.append("DEFAULT");
			} else {
				buf.append("RULE:[");
				buf.append(numOfComponents);
				buf.append(':');
				buf.append(format);
				buf.append(']');
				if (match != null) {
					buf.append('(');
					buf.append(match);
					buf.append(')');
				}
				if (fromPattern != null) {
					buf.append("s/");
					buf.append(fromPattern);
					buf.append('/');
					buf.append(toPattern);
					buf.append('/');
					if (repeat) {
						buf.append('g');
					}
				}
				if (toLowerCase) {
					buf.append("/L");
				}
			}
			return buf.toString();
		}

		/**
		 * Replace the numbered parameters of the form $n where n is from 1 to the
		 * length of params. Normal text is copied directly and $n is replaced by the
		 * corresponding parameter.
		 * 
		 * @param format the string to replace parameters again
		 * @param params the list of parameters
		 * @return the generated string with the parameter references replaced.
		 * @throws BadFormatString
		 */
		static String replaceParameters(String format, String[] params) throws BadFormatString {
			Matcher match = parameterPattern.matcher(format);
			int start = 0;
			StringBuilder result = new StringBuilder();
			while (start < format.length() && match.find(start)) {
				result.append(match.group(1));
				String paramNum = match.group(3);
				if (paramNum != null) {
					try {
						int num = Integer.parseInt(paramNum);
						if (num < 0 || num > params.length) {
							throw new BadFormatString("index " + num + " from " + format
									+ " is outside of the valid range 0 to " + (params.length - 1));
						}
						result.append(params[num]);
					} catch (NumberFormatException nfe) {
						throw new BadFormatString("bad format in username mapping in " + paramNum, nfe);
					}

				}
				start = match.end();
			}
			return result.toString();
		}

		/**
		 * Replace the matches of the from pattern in the base string with the value of
		 * the to string.
		 * 
		 * @param base   the string to transform
		 * @param from   the pattern to look for in the base string
		 * @param to     the string to replace matches of the pattern with
		 * @param repeat whether the substitution should be repeated
		 * @return
		 */
		static String replaceSubstitution(String base, Pattern from, String to, boolean repeat) {
			Matcher match = from.matcher(base);
			if (repeat) {
				return match.replaceAll(to);
			} else {
				return match.replaceFirst(to);
			}
		}

		/**
		 * Try to apply this rule to the given name represented as a parameter array.
		 * 
		 * @param params first element is the realm, second and later elements are are
		 *               the components of the name "a/b@FOO" -> {"FOO", "a", "b"}
		 * @return the short name if this rule applies or null
		 * @throws IOException throws if something is wrong with the rules
		 */
		String apply(String[] params) throws IOException {
			String result = null;
			if (isDefault) {
				if (defaultRealm.equals(params[0])) {
					result = params[1];
				}
			} else if (params.length - 1 == numOfComponents) {
				String base = replaceParameters(format, params);
				if (match == null || match.matcher(base).matches()) {
					if (fromPattern == null) {
						result = base;
					} else {
						result = replaceSubstitution(base, fromPattern, toPattern, repeat);
					}
				}
			}
			if (result != null && nonSimplePattern.matcher(result).find()) {
				LOG.info("Non-simple name {} after auth_to_local rule {}", result, this);
			}
			if (toLowerCase && result != null) {
				result = result.toLowerCase(Locale.ENGLISH);
			}
			return result;
		}
	}

	/**
	 * Create a name from the full Kerberos principal name.
	 * 
	 * @param name full Kerberos principal name.
	 */
	public KerberosName(String name) {
		Matcher match = nameParser.matcher(name);
		if (!match.matches()) {
			if (name.contains("@")) {
				throw new IllegalArgumentException("Malformed Kerberos name: " + name);
			} else {
				serviceName = name;
				hostName = null;
				realm = null;
			}
		} else {
			serviceName = match.group(1);
			hostName = match.group(3);
			realm = match.group(5);
		}
	}

	static List<Rule> parseRules(String rules) {
		List<Rule> result = new ArrayList<Rule>();
		String remaining = rules.trim();
		while (remaining.length() > 0) {
			Matcher matcher = ruleParser.matcher(remaining);
			if (!matcher.lookingAt()) {
				throw new IllegalArgumentException("Invalid rule: " + remaining);
			}
			if (matcher.group(2) != null) {
				result.add(new Rule());
			} else {
				result.add(new Rule(Integer.parseInt(matcher.group(4)), matcher.group(5), matcher.group(7),
						matcher.group(9), matcher.group(10), "g".equals(matcher.group(11)),
						"L".equals(matcher.group(12))));
			}
			remaining = remaining.substring(matcher.end());
		}
		return result;
	}

	public static void setRules(String ruleString) {
		rules = (ruleString != null) ? parseRules(ruleString) : null;
	}

	/**
	 * Get the translation of the principal name into an operating system user name.
	 * 
	 * @return the user name
	 * @throws IOException throws if something is wrong with the rules
	 */
	public String getShortName() throws IOException {
		String[] params;
		if (hostName == null) {
			// if it is already simple, just return it
			if (realm == null) {
				return serviceName;
			}
			params = new String[] { realm, serviceName };
		} else {
			params = new String[] { realm, serviceName, hostName };
		}
		for (Rule r : rules) {
			String result = r.apply(params);
			if (result != null) {
				return result;
			}
		}
		LOG.info("No auth_to_local rules applied to {}", this);
		return toString();
	}

	@SuppressWarnings("serial")
	public static class BadFormatString extends IOException {
		BadFormatString(String msg) {
			super(msg);
		}

		BadFormatString(String msg, Throwable err) {
			super(msg, err);
		}
	}

	@SuppressWarnings("serial")
	public static class NoMatchingRule extends IOException {
		NoMatchingRule(String msg) {
			super(msg);
		}
	}

}
