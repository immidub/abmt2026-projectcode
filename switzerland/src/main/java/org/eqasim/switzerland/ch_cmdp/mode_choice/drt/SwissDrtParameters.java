package org.eqasim.switzerland.ch_cmdp.mode_choice.drt;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.Purpose;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

sealed interface SwissDrtParameters permits SwissDrtParameters.Kagho, SwissDrtParameters.Erath {
    enum Specification {
        kagho, erath;

        static Specification parse(String name) {
            for (Specification specification : values()) {
                if (specification.name().equals(name)) {
                    return specification;
                }
            }
            throw new IllegalArgumentException("Swiss DRT specification must be kagho or erath, got: " + name);
        }
    }

    Transfer transfer();

    record Transfer(double utilityMultiplierToZurich, double ascAdjustmentZurichUtility, String justification) {
    }

    record Kagho(Transfer transfer, double ascUtility, double rideUtilityPerMinute, double waitUtilityPerMinute,
            double workUtility, double age60PlusUtility, double costUtilityPerChf, double rideDistanceExponent,
            double costDistanceExponent, double costIncomeExponent, double referenceDistanceKm,
            double referenceHouseholdIncomeChfPerMonth) implements SwissDrtParameters {
    }

    enum HalfFareTreatment {
        equation47, table56
    }

    record PurposeParameters(double rideUtilityPerMinute, double rideDistanceExponent, double referenceDistanceKm) {
    }

    record Erath(Transfer transfer, double ascUtility, double responseUtilityPerMinute,
            double accessEgressUtilityPerMinute, double costUtilityPerChf, double costDistanceExponent,
            double halfFareUtility, HalfFareTreatment halfFareTreatment,
            Map<Purpose, PurposeParameters> purposes) implements SwissDrtParameters {
    }

    static SwissDrtParameters load(Specification selected, URL url) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var stream = url.openStream()) {
            return parse(selected, new Yaml(new SafeConstructor(options)).load(stream));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid Swiss DRT parameter file " + url + ": " + e.getMessage(), e);
        }
    }

    private static SwissDrtParameters parse(Specification selected, Object data) {
        var root = mapping(data, "root", "schemaVersion", "specification", "coefficients", "transfer", "halfFareTreatment", "purposes");
        if (!Integer.valueOf(1).equals(root.get("schemaVersion"))) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        var declared = Specification.parse(requiredText(root, "specification"));
        if (selected != declared) {
            throw new IllegalArgumentException("Selected " + selected + " but parameter file declares " + declared);
        }

        var transferData = mapping(root.get("transfer"), "transfer", "utilityMultiplierToZurich",
                "ascAdjustmentZurichUtility", "justification");
        double scale = finiteNumber(transferData, "utilityMultiplierToZurich");
        if (scale <= 0) {
            throw new IllegalArgumentException("utilityMultiplierToZurich must be positive");
        }
        var transfer = new Transfer(scale, finiteNumber(transferData, "ascAdjustmentZurichUtility"),
                requiredText(transferData, "justification"));

        return switch (selected) {
            case kagho -> {
                if (root.containsKey("halfFareTreatment") || root.containsKey("purposes")) {
                    throw new IllegalArgumentException("Kagho does not accept halfFareTreatment or purposes");
                }
                var c = mapping(root.get("coefficients"), "Kagho coefficients", "ascUtility",
                        "rideUtilityPerMinute", "waitUtilityPerMinute", "workUtility", "age60PlusUtility",
                        "costUtilityPerChf", "rideDistanceExponent", "costDistanceExponent", "costIncomeExponent",
                        "referenceDistanceKm", "referenceHouseholdIncomeChfPerMonth");
                yield new Kagho(transfer, finiteNumber(c, "ascUtility"), finiteNumber(c, "rideUtilityPerMinute"),
                        finiteNumber(c, "waitUtilityPerMinute"), finiteNumber(c, "workUtility"),
                        finiteNumber(c, "age60PlusUtility"), finiteNumber(c, "costUtilityPerChf"),
                        finiteNumber(c, "rideDistanceExponent"), finiteNumber(c, "costDistanceExponent"),
                        finiteNumber(c, "costIncomeExponent"), positiveNumber(c, "referenceDistanceKm"),
                        positiveNumber(c, "referenceHouseholdIncomeChfPerMonth"));
            }
            case erath -> {
                var c = mapping(root.get("coefficients"), "Erath coefficients", "ascUtility",
                        "responseUtilityPerMinute", "accessEgressUtilityPerMinute", "costUtilityPerChf",
                        "costDistanceExponent", "halfFareUtility");
                var purposes = mapping(root.get("purposes"), "Erath purposes", "commuting", "leisure",
                        "shopping", "other");
                var parsed = new java.util.EnumMap<Purpose, PurposeParameters>(Purpose.class);
                for (Purpose purpose : Purpose.values()) {
                    var p = mapping(purposes.get(purpose.name()), purpose.name(), "rideUtilityPerMinute",
                            "rideDistanceExponent", "referenceDistanceKm");
                    parsed.put(purpose, new PurposeParameters(finiteNumber(p, "rideUtilityPerMinute"),
                            finiteNumber(p, "rideDistanceExponent"), positiveNumber(p, "referenceDistanceKm")));
                }
                yield new Erath(transfer, finiteNumber(c, "ascUtility"), finiteNumber(c, "responseUtilityPerMinute"),
                        finiteNumber(c, "accessEgressUtilityPerMinute"), finiteNumber(c, "costUtilityPerChf"),
                        finiteNumber(c, "costDistanceExponent"), finiteNumber(c, "halfFareUtility"),
                        HalfFareTreatment.valueOf(requiredText(root, "halfFareTreatment")), Map.copyOf(parsed));
            }
        };
    }

    private static double positiveNumber(Map<?, ?> map, String key) {
        double value = finiteNumber(map, key);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static Map<?, ?> mapping(Object value, String name, String... keys) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be a YAML mapping");
        }
        for (Object key : map.keySet()) {
            if (!List.of(keys).contains(key)) {
                throw new IllegalArgumentException("Unknown key in " + name + ": " + key);
            }
        }
        return map;
    }

    private static String requiredText(Map<?, ?> map, String key) {
        if (map.get(key) instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException(key + " must be a nonblank string");
    }

    private static double finiteNumber(Map<?, ?> map, String key) {
        if (map.get(key) instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException(key + " must be a finite number");
    }
}
