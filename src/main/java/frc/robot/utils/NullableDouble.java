// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.utils;

import edu.wpi.first.util.struct.StructSerializable;

/** Silly */
public class NullableDouble implements StructSerializable {
  final boolean isNull;
  final Double value;

  public NullableDouble(Double value) {
    isNull = value == null ? true : false;
    this.value = value;
  }

  public Double get() {
    return isNull ? null : value;
  }

  public static final NullableDoubleStruct struct = new NullableDoubleStruct();
}
