load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "ProjectViewTargetsSectionParserTest",
    size = "small",
    srcs = ["ProjectViewTargetsSectionParserTest.java"],
    runtime_deps = [
        "@maven//:junit_junit",
    ],
    deps = [
        "//projectview/src/main/java/org/jetbrains/bsp/bazel/projectview/parser/sections",
    ],
)

java_test(
    name = "ProjectViewSingletonSectionParserTest",
    size = "small",
    srcs = ["ProjectViewSingletonSectionParserTest.java"],
    runtime_deps = [
        "@maven//:junit_junit",
    ],
    deps = [
        "//projectview/src/main/java/org/jetbrains/bsp/bazel/projectview/parser/sections",
    ],
)
