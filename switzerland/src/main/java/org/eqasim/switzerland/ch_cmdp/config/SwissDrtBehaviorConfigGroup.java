package org.eqasim.switzerland.ch_cmdp.config;

import org.matsim.core.config.ReflectiveConfigGroup;

public final class SwissDrtBehaviorConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "eqasim:swissDrtBehavior";
    public static final String SPECIFICATION = "specification";
    public static final String PARAMETERS_PATH = "parametersPath";

    private String specification;
    private String parametersPath;
    private double pickupWaitMinutes = Double.NaN;
    private double poolingDelayMinutes = Double.NaN;
    private double fareChfPerRoutedKm = Double.NaN;

    public SwissDrtBehaviorConfigGroup() {
        super(GROUP_NAME);
    }

    @StringGetter(SPECIFICATION)
    public String getSpecification() {
        return specification;
    }

    @StringSetter(SPECIFICATION)
    public void setSpecification(String specification) {
        this.specification = specification;
    }

    @StringGetter(PARAMETERS_PATH)
    public String getParametersPath() {
        return parametersPath;
    }

    @StringSetter(PARAMETERS_PATH)
    public void setParametersPath(String parametersPath) {
        this.parametersPath = parametersPath;
    }

    @StringGetter("pickupWaitMinutes")
    public double getPickupWaitMinutes() {
        return pickupWaitMinutes;
    }

    @StringSetter("pickupWaitMinutes")
    public void setPickupWaitMinutes(double value) {
        pickupWaitMinutes = value;
    }

    @StringGetter("poolingDelayMinutes")
    public double getPoolingDelayMinutes() {
        return poolingDelayMinutes;
    }

    @StringSetter("poolingDelayMinutes")
    public void setPoolingDelayMinutes(double value) {
        poolingDelayMinutes = value;
    }

    @StringGetter("fareChfPerRoutedKm")
    public double getFareChfPerRoutedKm() {
        return fareChfPerRoutedKm;
    }

    @StringSetter("fareChfPerRoutedKm")
    public void setFareChfPerRoutedKm(double value) {
        fareChfPerRoutedKm = value;
    }

    public void validateInputs() {
        requireNonnegative(pickupWaitMinutes, "pickupWaitMinutes");
        requireNonnegative(poolingDelayMinutes, "poolingDelayMinutes");
        requireNonnegative(fareChfPerRoutedKm, "fareChfPerRoutedKm");
    }

    private static void requireNonnegative(double value, String key) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(GROUP_NAME + "." + key + " must be finite and nonnegative");
        }
    }
}
