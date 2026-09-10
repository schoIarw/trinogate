package com.trinogate.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Column definition inside {@link QueryResults}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Column {

    public String name;
    public String type;
    /** Raw type signature object; carried through from the backend (ClientTypeSignature). */
    public Object typeSignature;

    public Column() {}

    public Column(String name, String type, Object typeSignature) {
        this.name = name;
        this.type = type;
        this.typeSignature = typeSignature;
    }
}
