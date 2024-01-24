// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.utils.autoaim;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.geometry.Rotation2d;
import java.util.TreeMap;

/*
 * Class for a TreeMap of ShotData that interpolates between unknown values
 * Modified version of WPILib's InterpolatingTreeMap
 */
public class InterpolatingShotTree {

  private final TreeMap<Double, ShotData> map = new TreeMap<>();

  public InterpolatingShotTree() {}

  public void put(Double key, ShotData value) {
    map.put(key, value);
  }

  public ShotData get(Double key) {
    ShotData val = map.get(key);
    if (val == null) {
      Double ceilingKey = map.ceilingKey(key);
      Double floorKey = map.floorKey(key);

      if (ceilingKey == null && floorKey == null) {
        return null;
      }
      if (ceilingKey == null) {
        return map.get(floorKey);
      }
      if (floorKey == null) {
        return map.get(ceilingKey);
      }
      ShotData floor = map.get(floorKey);
      ShotData ceiling = map.get(ceilingKey);

      return interpolate(floor, ceiling, inverseInterpolate(ceilingKey, key, floorKey));
    } else {
      return val;
    }
  }

  public void clear() {
    map.clear();
  }

  private ShotData interpolate(ShotData startValue, ShotData endValue, double t) {
    return new ShotData(
        Rotation2d.fromRadians(
            MathUtil.interpolate(
                startValue.getAngle().getRadians(), endValue.getAngle().getRadians(), t)),
        MathUtil.interpolate(startValue.getRPM(), endValue.getRPM(), t),
        MathUtil.interpolate(startValue.getFlightTime(), endValue.getFlightTime(), t));
  }

  private double inverseInterpolate(Double up, Double q, Double down) {
    double upperToLower = up.doubleValue() - down.doubleValue();
    if (upperToLower <= 0) {
      return 0.0;
    }
    double queryToLower = q.doubleValue() - down.doubleValue();
    if (queryToLower <= 0) {
      return 0.0;
    }
    return queryToLower / upperToLower;
  }
}
