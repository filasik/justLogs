package me.skerik.justlogs.query;

/**
 * A query the user wrote is wrong. {@code code} is the suffix of the message key {@code query.error.<code>},
 * {@code arg} fills the {@code <arg>} placeholder (the offending token or value).
 */
public final class QuerySyntaxException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String arg;

    public QuerySyntaxException(String code, String arg) {
        super(code + ": " + arg);
        this.code = code;
        this.arg = arg;
    }

    public String code() {
        return code;
    }

    public String arg() {
        return arg;
    }
}
