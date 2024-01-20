// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.utils.autoaim;

import edu.wpi.first.math.interpolation.Interpolatable;

/*
 * Holds data about each shot in which we interpolate with
 */
public class ShotData implements Interpolatable<ShotData> {

  private double angle;
  private double rotationsPerSecond;
  private double flightTime;

  public ShotData(double angle, double rotationsPerSecond, double flightTime) {
    this.angle = angle;
    this.rotationsPerSecond = rotationsPerSecond;
    this.flightTime = flightTime;
  }

  public double getAngle() {
    return angle;
  }

  public double getRPM() {
    return rotationsPerSecond;
  }

  public double getFlightTime(){
    return flightTime;
  }


  @Override
  public ShotData interpolate(ShotData endValue, double t) {
    return new ShotData(
        ((endValue.getAngle() - angle) * t) + angle,
        ((endValue.getRPM() - rotationsPerSecond) * t) + rotationsPerSecond,
        ((endValue.getFlightTime() - flightTime) * t) + flightTime);
  }

  public String toString() {
    return "" + getAngle() + " " + getRPM() + " " + getFlightTime();
  }
}
