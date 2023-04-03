package de.iter8.maven.google_pojo;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class ApiBuilder {
    private final String apiName;
    private final String apiVersion;
    private final String basePackageName;
}
