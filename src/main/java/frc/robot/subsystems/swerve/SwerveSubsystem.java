// Copyright 2021-2023 FRC 6328
// http://github.com/Mechanical-Advantage
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// version 3 as published by the Free Software Foundation or
// available in the root directory of this project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.

package frc.robot.subsystems.swerve;

import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;

import com.choreo.lib.Choreo;
import com.choreo.lib.ChoreoControlFunction;
import com.choreo.lib.ChoreoTrajectory;
import com.ctre.phoenix6.SignalLogger;
import com.google.common.collect.Streams;
import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.util.HolonomicPathFollowerConfig;
import com.pathplanner.lib.util.PIDConstants;
import com.pathplanner.lib.util.PathPlannerLogging;
import com.pathplanner.lib.util.ReplanningConfig;
import edu.wpi.first.apriltag.AprilTagFieldLayout;
import edu.wpi.first.apriltag.AprilTagFields;
import edu.wpi.first.math.MatBuilder;
import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.Nat;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.Vector;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.controller.ProfiledPIDController;
import edu.wpi.first.math.estimator.SwerveDrivePoseEstimator;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Translation3d;
import edu.wpi.first.math.geometry.Twist2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.math.numbers.N5;
import edu.wpi.first.math.trajectory.TrapezoidProfile.Constraints;
import edu.wpi.first.math.trajectory.TrapezoidProfile.State;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.units.Measure;
import edu.wpi.first.units.Voltage;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj.Filesystem;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.FunctionalCommand;
import edu.wpi.first.wpilibj2.command.Subsystem;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine.Direction;
import frc.robot.FieldConstants;
import frc.robot.Robot;
import frc.robot.Robot.RobotMode;
import frc.robot.subsystems.swerve.Module.ModuleConstants;
import frc.robot.subsystems.swerve.OdometryThreadIO.OdometryThreadIOInputs;
import frc.robot.subsystems.swerve.PhoenixOdometryThread.SignalID;
import frc.robot.subsystems.swerve.PhoenixOdometryThread.SignalType;
import frc.robot.subsystems.swerve.SwerveSubsystem.AutoAimStates;
import frc.robot.subsystems.vision.Vision;
import frc.robot.subsystems.vision.Vision.VisionConstants;
import frc.robot.subsystems.vision.VisionHelper;
import frc.robot.subsystems.vision.VisionIO;
import frc.robot.subsystems.vision.VisionIOReal;
import frc.robot.subsystems.vision.VisionIOSim;
import frc.robot.utils.Tracer;
import frc.robot.utils.autoaim.AutoAim;
import frc.robot.utils.autoaim.ShotData;
import java.io.File;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.littletonrobotics.junction.AutoLogOutput;
import org.littletonrobotics.junction.Logger;
import org.photonvision.targeting.PhotonPipelineResult;

public class SwerveSubsystem extends SubsystemBase {

  public class AutoAimStates {
    public static double lookaheadTime = AutoAim.LOOKAHEAD_TIME_SECONDS;

    public static ShotData curShotData = new ShotData(new Rotation2d(), 0, 0, 0);
    public static ChassisSpeeds curShotSpeeds = new ChassisSpeeds();

    public static Pose2d endingPose = new Pose2d();
    public static Pose2d virtualTarget = new Pose2d();
  }

  // Drivebase constants
  public static final double MAX_LINEAR_SPEED = Units.feetToMeters(16);
  public static final double MAX_LINEAR_ACCELERATION = 8.0;
  public static final double TRACK_WIDTH_X = Units.inchesToMeters(21.75);
  public static final double TRACK_WIDTH_Y = Units.inchesToMeters(21.25);
  public static final double DRIVE_BASE_RADIUS =
      Math.hypot(TRACK_WIDTH_X / 2.0, TRACK_WIDTH_Y / 2.0);
  public static final double MAX_ANGULAR_SPEED = MAX_LINEAR_SPEED / DRIVE_BASE_RADIUS;
  public static final double MAX_ANGULAR_ACCELERATION = MAX_LINEAR_ACCELERATION / DRIVE_BASE_RADIUS;
  public static final double MAX_AUTOAIM_SPEED = MAX_LINEAR_SPEED / 4;
  // Hardware constants
  public static final int PIGEON_ID = 0;

  public static final double HEADING_VELOCITY_KP = 4.0;
  public static final double HEADING_VOLTAGE_KP = 4.0;

  public static final ModuleConstants frontLeft =
      new ModuleConstants(0, "Front Left", 0, 1, 0, Rotation2d.fromRotations(0.377930));
  public static final ModuleConstants frontRight =
      new ModuleConstants(1, "Front Right", 2, 3, 1, Rotation2d.fromRotations(-0.071289));
  public static final ModuleConstants backLeft =
      new ModuleConstants(2, "Back Left", 4, 5, 2, Rotation2d.fromRotations(0.550781));
  public static final ModuleConstants backRight =
      new ModuleConstants(3, "Back Right", 6, 7, 3, Rotation2d.fromRotations(-0.481689));

  private final GyroIO gyroIO;
  private final GyroIOInputsAutoLogged gyroInputs = new GyroIOInputsAutoLogged();
  private final Module[] modules; // FL, FR, BL, BR
  private final OdometryThreadIO odoThread;
  private final OdometryThreadIOInputs odoThreadInputs = new OdometryThreadIOInputs();

  private SwerveDriveKinematics kinematics = new SwerveDriveKinematics(getModuleTranslations());

  /** For delta tracking with PhoenixOdometryThread* */
  private SwerveModulePosition[] lastModulePositions =
      new SwerveModulePosition[] {
        new SwerveModulePosition(),
        new SwerveModulePosition(),
        new SwerveModulePosition(),
        new SwerveModulePosition()
      };

  private Rotation2d rawGyroRotation = new Rotation2d();
  private Rotation2d lastGyroRotation = new Rotation2d();

  private final Vision[] cameras;
  public static AprilTagFieldLayout fieldTags;

  private SwerveDrivePoseEstimator estimator =
      new SwerveDrivePoseEstimator(kinematics, rawGyroRotation, lastModulePositions, new Pose2d());
  Vector<N3> odoStdDevs = VecBuilder.fill(0.3, 0.3, 0.01);
  private double lastEstTimestamp = 0.0;
  private double lastOdometryUpdateTimestamp = 0.0;

  public static final Matrix<N3, N3> LEFT_CAMERA_MATRIX =
      MatBuilder.fill(
          Nat.N3(),
          Nat.N3(),
          915.2126592056358,
          0.0,
          841.560216921862,
          0.0,
          913.9556728013187,
          648.2330358379004,
          0.0,
          0.0,
          1.0);
  public static final Matrix<N5, N1> LEFT_DIST_COEFFS =
      MatBuilder.fill(
          Nat.N5(),
          Nat.N1(),
          0.0576413369828492,
          -0.07356597379196807,
          -6.669129885790735E-4,
          6.491281122640802E-4,
          0.03731824873787814); // Last 3 values have been truncated
  public static final Matrix<N3, N3> RIGHT_CAMERA_MATRIX =
      MatBuilder.fill(
          Nat.N3(),
          Nat.N3(),
          902.0832829888818,
          0.0,
          611.9702186077134,
          0.0,
          902.2731968281233,
          400.755534902121,
          0.0,
          0.0,
          1.0);
  public static final Matrix<N5, N1> RIGHT_DIST_COEFFS =
      MatBuilder.fill(
          Nat.N5(),
          Nat.N1(),
          0.05398335403070431,
          -0.07589158973947994,
          -0.003081304772847505,
          -0.0010797674400397023,
          0.015185486932866137); // Last 3 values have been truncated
  public static final VisionConstants leftCamConstants =
      new VisionConstants(
          "Left_Camera",
          new Transform3d(
              new Translation3d(
                  Units.inchesToMeters(-10.386),
                  Units.inchesToMeters(10.380),
                  Units.inchesToMeters(7.381)),
              new Rotation3d(
                  Units.degreesToRadians(0.0),
                  Units.degreesToRadians(-28.125),
                  Units.degreesToRadians(120))),
          LEFT_CAMERA_MATRIX,
          LEFT_DIST_COEFFS);
  public static final VisionConstants rightCamConstants =
      new VisionConstants(
          "Right_Camera",
          new Transform3d(
              new Translation3d(
                  Units.inchesToMeters(-10.597),
                  Units.inchesToMeters(-10.143),
                  Units.inchesToMeters(7.384)),
              new Rotation3d(0, Units.degreesToRadians(-28.125), Units.degreesToRadians(210))),
          RIGHT_CAMERA_MATRIX,
          RIGHT_DIST_COEFFS);

  private final SysIdRoutine moduleSteerRoutine;
  private final SysIdRoutine driveRoutine;

  public SwerveSubsystem(
      GyroIO gyroIO, VisionIO[] visionIOs, ModuleIO[] moduleIOs, OdometryThreadIO odoThread) {
    this.gyroIO = gyroIO;
    this.odoThread = odoThread;
    odoThread.start();
    cameras = new Vision[visionIOs.length];
    new AutoAim();
    modules = new Module[moduleIOs.length];

    for (int i = 0; i < moduleIOs.length; i++) {
      modules[i] = new Module(moduleIOs[i]);
    }
    for (int i = 0; i < visionIOs.length; i++) {
      cameras[i] = new Vision(visionIOs[i]);
    }

    // mildly questionable
    VisionIOSim.pose = this::getPose3d;

    AutoBuilder.configureHolonomic(
        this::getPose, // Robot pose supplier
        this::setPose, // Method to reset odometry (will be called if your auto has a starting pose)
        this::getRobotRelativeSpeeds, // ChassisSpeeds supplier. MUST BE ROBOT RELATIVE
        this::runVelocity, // Method that will drive the robot given ROBOT RELATIVE
        // ChassisSpeeds
        new HolonomicPathFollowerConfig( // HolonomicPathFollowerConfig, this should likely live in
            // your Constants class
            new PIDConstants(10.0, 0.0, 0.0), // Translation PID constants
            new PIDConstants(20.0, 0.0, 0.0), // Rotation PID constants
            MAX_LINEAR_SPEED, // Max module speed, in m/s
            DRIVE_BASE_RADIUS, // Drive base radius in meters. Distance from robot center to
            // furthest module.
            new ReplanningConfig(
                false, false) // Default path replanning config. See the API for the options
            // here
            ),
        () -> DriverStation.getAlliance().orElse(Alliance.Blue) == Alliance.Red,
        this // Reference to this subsystem to set requirements
        );

    PathPlannerLogging.setLogTargetPoseCallback(
        (pose) -> {
          Logger.recordOutput("PathPlanner/Target", pose);
          Logger.recordOutput(
              "PathPlanner/Absolute Translation Error",
              pose.minus(getPose()).getTranslation().getNorm());
        });
    PathPlannerLogging.setLogActivePathCallback(
        (path) -> Logger.recordOutput("PathPlanner/Active Path", path.toArray(Pose2d[]::new)));
    Logger.recordOutput("PathPlanner/Target", new Pose2d());
    Logger.recordOutput("PathPlanner/Absolute Translation Error", 0.0);

    moduleSteerRoutine =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                null, // Default ramp rate is acceptable
                Volts.of(8.0),
                Seconds.of(6.0), // Default timeout is acceptable
                // Log state with Phoenix SignalLogger class
                (state) -> SignalLogger.writeString("state", state.toString())),
            new SysIdRoutine.Mechanism(
                (Measure<Voltage> volts) -> modules[0].runSteerCharacterization(volts.in(Volts)),
                null,
                this));
    driveRoutine =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                null, // Default ramp rate is acceptable
                Volts.of(3.5),
                Seconds.of(3.5),
                // Log state with Phoenix SignalLogger class
                (state) -> SignalLogger.writeString("state", state.toString())),
            new SysIdRoutine.Mechanism(
                (Measure<Voltage> volts) -> runDriveCharacterizationVolts(volts.in(Volts)),
                null,
                this));
    try {
      fieldTags =
          new AprilTagFieldLayout(
              Filesystem.getDeployDirectory()
                  .toPath()
                  .resolve("vision" + File.separator + "2024-crescendo.json"));
      System.out.println("Successfully loaded tag map");
    } catch (Exception e) {
      System.err.println("Failed to load tag map");
      fieldTags = AprilTagFields.k2024Crescendo.loadAprilTagLayoutField();
    }
  }

  /**
   * Constructs an array of swerve module ios corresponding to the real robot.
   *
   * @return The array of swerve module ios.
   */
  public static ModuleIO[] createTalonFXModules() {
    return new ModuleIO[] {
      new ModuleIOReal(frontLeft),
      new ModuleIOReal(frontRight),
      new ModuleIOReal(backLeft),
      new ModuleIOReal(backRight)
    };
  }

  /**
   * Constructs an array of swerve module ios corresponding to a simulated robot.
   *
   * @return The array of swerve module ios.
   */
  public static ModuleIO[] createSimModules() {
    return new ModuleIO[] {
      new ModuleIOSim(frontLeft),
      new ModuleIOSim(frontRight),
      new ModuleIOSim(backLeft),
      new ModuleIOSim(backRight)
    };
  }

  /**
   * Constructs an array of vision IOs corresponding to the real robot.
   *
   * @return The array of vision IOs.
   */
  public static VisionIO[] createRealCameras() {
    return new VisionIO[] {new VisionIOReal(leftCamConstants), new VisionIOReal(rightCamConstants)};
  }

  /**
   * Constructs an array of vision IOs corresponding to the simulated robot.
   *
   * @return The array of vision IOs.
   */
  public static VisionIO[] createSimCameras() {
    return new VisionIO[] {new VisionIOSim(leftCamConstants), new VisionIOSim(rightCamConstants)};
  }

  public void periodic() {
    Tracer.trace(
        "SwervePeriodic",
        () -> {
          for (var camera : cameras) {
            Tracer.trace("Update cam inputs", camera::updateInputs);
            Tracer.trace("Process cam inputs", camera::processInputs);
          }
          Tracer.trace(
              "Update odo inputs",
              () -> odoThread.updateInputs(odoThreadInputs, lastOdometryUpdateTimestamp));
          Logger.processInputs("Async Odo", odoThreadInputs);
          if (!odoThreadInputs.sampledStates.isEmpty()) {
            lastOdometryUpdateTimestamp =
                odoThreadInputs
                    .sampledStates
                    .get(odoThreadInputs.sampledStates.size() - 1)
                    .timestamp();
          }
          Tracer.trace("update gyro inputs", () -> gyroIO.updateInputs(gyroInputs));
          for (int i = 0; i < modules.length; i++) {
            Tracer.trace(
                "SwerveModule update inputs from " + modules[i].getPrefix() + " Module",
                modules[i]::updateInputs);
          }
          Logger.processInputs("Swerve/Gyro", gyroInputs);
          for (int i = 0; i < modules.length; i++) {
            Tracer.trace(
                "SwerveModule periodic from " + modules[i].getPrefix() + " Module",
                modules[i]::periodic);
          }

          // Stop moving when disabled
          if (DriverStation.isDisabled()) {
            for (var module : modules) {
              module.stop();
            }
          }
          // Log empty setpoint states when disabled
          if (DriverStation.isDisabled()) {
            Logger.recordOutput("SwerveStates/Setpoints", new SwerveModuleState[] {});
            Logger.recordOutput("SwerveStates/SetpointsOptimized", new SwerveModuleState[] {});
          }

          Logger.recordOutput("ShotData/Angle", AutoAimStates.curShotData.getRotation());
          Logger.recordOutput("ShotData/Left RPM", AutoAimStates.curShotData.getLeftRPS());
          Logger.recordOutput("ShotData/Right RPM", AutoAimStates.curShotData.getRightRPS());
          Logger.recordOutput(
              "ShotData/Flight Time", AutoAimStates.curShotData.getFlightTimeSeconds());
          Logger.recordOutput("ShotData/Lookahead", AutoAimStates.lookaheadTime);

          Tracer.trace("Update odometry", this::updateOdometry);
          Tracer.trace("update vision", this::updateVision);
        });
  }

  private void updateOdometry() {
    Logger.recordOutput("Swerve/Updates Since Last", odoThreadInputs.sampledStates.size());
    var sampleStates = odoThreadInputs.sampledStates;
    var i = 0;
    for (var sample : sampleStates) {
      i++;
      // Read wheel deltas from each module
      SwerveModulePosition[] modulePositions = new SwerveModulePosition[4];
      SwerveModulePosition[] moduleDeltas =
          new SwerveModulePosition[4]; // change in positions since the last update
      boolean hasNullModulePosition = false;
      // Technically we could have not 4 modules worth of data here but im not dealing w that
      for (int moduleIndex = 0; moduleIndex < 4; moduleIndex++) {
        var dist = sample.values().get(new SignalID(SignalType.DRIVE, moduleIndex));
        if (dist == null) {
          // No value at this timestamp
          hasNullModulePosition = true;

          Logger.recordOutput("Odometry/Received Update From Module " + moduleIndex, false);
          break;
        }
        var rot = sample.values().get(new SignalID(SignalType.STEER, moduleIndex));
        if (rot == null) {
          // No value at this timestamp
          hasNullModulePosition = true;

          Logger.recordOutput("Odometry/Received Update From Module " + moduleIndex, false);
          break;
        }
        modulePositions[moduleIndex] =
            new SwerveModulePosition(
                dist, Rotation2d.fromRotations(rot)); // gets positions from the thread, NOT inputs
        Logger.recordOutput("Odometry/Received Update From Module " + moduleIndex, true);
        moduleDeltas[moduleIndex] =
            new SwerveModulePosition(
                modulePositions[moduleIndex].distanceMeters
                    - lastModulePositions[moduleIndex].distanceMeters,
                modulePositions[moduleIndex].angle);
        lastModulePositions[moduleIndex] = modulePositions[moduleIndex];
      }
      if (hasNullModulePosition) {
        if (!gyroInputs.connected
            || sample.values().get(new SignalID(SignalType.GYRO, OdometryThreadIO.GYRO_MODULE_ID))
                == null) {
          Logger.recordOutput("Odometry/Received Gyro Update", false);
          // no modules and no gyro so we're just sad :(
        } else {
          Logger.recordOutput("Odometry/Received Gyro Update", true);
          // null here is checked by if clause
          rawGyroRotation =
              Rotation2d.fromDegrees(sample.values().get(new SignalID(SignalType.GYRO, -1)));
          lastGyroRotation = rawGyroRotation;
          Logger.recordOutput("Odometry/Gyro Rotation", lastGyroRotation);
          Tracer.trace(
              "update estimator",
              () ->
                  estimator.updateWithTime(
                      sample.timestamp(), rawGyroRotation, lastModulePositions));
        }
        continue;
      }

      // The twist represents the motion of the robot since the last
      // sample in x, y, and theta based only on the modules, without
      // the gyro. The gyro is always disconnected in simulation.
      Twist2d twist = kinematics.toTwist2d(moduleDeltas);
      if (!gyroInputs.connected
          || sample.values().get(new SignalID(SignalType.GYRO, OdometryThreadIO.GYRO_MODULE_ID))
              == null) {
        Logger.recordOutput("Odometry/Received Gyro Update", false);
        // We don't have a complete set of data, so just use the module rotations
        rawGyroRotation = rawGyroRotation.plus(new Rotation2d(twist.dtheta));
      } else {
        Logger.recordOutput("Odometry/Received Gyro Update", true);
        rawGyroRotation =
            Rotation2d.fromDegrees(
                sample
                    .values()
                    .get(new SignalID(SignalType.GYRO, OdometryThreadIO.GYRO_MODULE_ID)));
        twist =
            new Twist2d(twist.dx, twist.dy, rawGyroRotation.minus(lastGyroRotation).getRadians());
      }
      // Apply the twist (change since last sample) to the current pose
      lastGyroRotation = rawGyroRotation;
      Logger.recordOutput("Odometry/Gyro Rotation", lastGyroRotation);
      // Apply update
      estimator.updateWithTime(sample.timestamp(), rawGyroRotation, modulePositions);
    }
  }

  private void updateVision() {
    for (var camera : cameras) {
      PhotonPipelineResult result =
          new PhotonPipelineResult(camera.inputs.latency, camera.inputs.targets);
      result.setTimestampSeconds(camera.inputs.timestamp);
      boolean newResult = Math.abs(camera.inputs.timestamp - lastEstTimestamp) > 1e-5;
      try {
        var estPose = camera.update(result);
        var visionPose = estPose.get().estimatedPose;
        // Sets the pose on the sim field
        camera.setSimPose(estPose, camera, newResult);
        Logger.recordOutput("Vision/Vision Pose From " + camera.getName(), visionPose);
        Logger.recordOutput("Vision/Vision Pose2d From " + camera.getName(), visionPose.toPose2d());
        estimator.addVisionMeasurement(
            visionPose.toPose2d(),
            camera.inputs.timestamp,
            VisionHelper.findVisionMeasurementStdDevs(estPose.get()));
        if (newResult) lastEstTimestamp = camera.inputs.timestamp;
      } catch (NoSuchElementException e) {
      }
    }
  }

  private void runVelocity(ChassisSpeeds speeds) {
    // Calculate module setpoints
    ChassisSpeeds discreteSpeeds = ChassisSpeeds.discretize(speeds, 0.02);
    SwerveModuleState[] setpointStates = kinematics.toSwerveModuleStates(discreteSpeeds);
    SwerveDriveKinematics.desaturateWheelSpeeds(setpointStates, MAX_LINEAR_SPEED);

    Logger.recordOutput("Swerve/Target Speeds", discreteSpeeds);
    Logger.recordOutput("Swerve/Speed Error", discreteSpeeds.minus(getVelocity()));
    Logger.recordOutput(
        "Swerve/Target Chassis Speeds Field Relative",
        ChassisSpeeds.fromRobotRelativeSpeeds(discreteSpeeds, getRotation()));

    // Send setpoints to modules
    SwerveModuleState[] optimizedSetpointStates =
        Streams.zip(
                Arrays.stream(modules), Arrays.stream(setpointStates), (m, s) -> m.runSetpoint(s))
            .toArray(SwerveModuleState[]::new);

    // Log setpoint states
    Logger.recordOutput("SwerveStates/Setpoints", setpointStates);
    Logger.recordOutput("SwerveStates/SetpointsOptimized", optimizedSetpointStates);
  }

  /**
   * Runs the drive at the desired velocity.
   *
   * @param speeds Speeds in meters/sec
   */
  public Command runVelocityCmd(Supplier<ChassisSpeeds> speeds) {
    return this.run(() -> runVelocity(speeds.get()));
  }

  /** Stops the drive. */
  public Command stopCmd() {
    return runVelocityCmd(ChassisSpeeds::new);
  }

  public Command runVelocityFieldRelative(Supplier<ChassisSpeeds> speeds) {
    return this.runVelocityCmd(
        () -> ChassisSpeeds.fromFieldRelativeSpeeds(speeds.get(), getPose().getRotation()));
  }

  public Command runVelocityTeleopFieldRelative(Supplier<ChassisSpeeds> speeds) {
    return this.runVelocityCmd(
        () ->
            ChassisSpeeds.fromFieldRelativeSpeeds(
                speeds.get(),
                DriverStation.getAlliance().orElse(Alliance.Blue) == Alliance.Blue
                    ? getPose().getRotation()
                    : getPose().getRotation().minus(Rotation2d.fromDegrees(180))));
  }

  public Command runVoltageTeleopFieldRelative(Supplier<ChassisSpeeds> speeds) {
    return this.run(
        () -> {
          var allianceSpeeds =
              ChassisSpeeds.fromFieldRelativeSpeeds(
                  speeds.get(),
                  DriverStation.getAlliance().orElse(Alliance.Blue) == Alliance.Blue
                      ? getPose().getRotation()
                      : getPose().getRotation().minus(Rotation2d.fromDegrees(180)));
          // Calculate module setpoints
          ChassisSpeeds discreteSpeeds = ChassisSpeeds.discretize(allianceSpeeds, 0.02);
          SwerveModuleState[] setpointStates = kinematics.toSwerveModuleStates(discreteSpeeds);
          SwerveDriveKinematics.desaturateWheelSpeeds(setpointStates, MAX_LINEAR_SPEED);

          Logger.recordOutput("Swerve/Target Speeds", discreteSpeeds);
          Logger.recordOutput("Swerve/Speed Error", discreteSpeeds.minus(getVelocity()));
          Logger.recordOutput(
              "Swerve/Target Chassis Speeds Field Relative",
              ChassisSpeeds.fromRobotRelativeSpeeds(discreteSpeeds, getRotation()));

          final boolean focEnable =
              Math.sqrt(
                      Math.pow(this.getVelocity().vxMetersPerSecond, 2)
                          + Math.pow(this.getVelocity().vyMetersPerSecond, 2))
                  < MAX_LINEAR_SPEED * 0.9;

          // Send setpoints to modules
          SwerveModuleState[] optimizedSetpointStates =
              Streams.zip(
                      Arrays.stream(modules),
                      Arrays.stream(setpointStates),
                      (m, s) ->
                          m.runVoltageSetpoint(
                              new SwerveModuleState(
                                  s.speedMetersPerSecond * 12.0 / MAX_LINEAR_SPEED, s.angle),
                              focEnable))
                  .toArray(SwerveModuleState[]::new);

          // Log setpoint states
          Logger.recordOutput("SwerveStates/Setpoints", setpointStates);
          Logger.recordOutput("SwerveStates/SetpointsOptimized", optimizedSetpointStates);
        });
  }

  /**
   * Stops the drive and turns the modules to an X arrangement to resist movement. The modules will
   * return to their normal orientations the next time a nonzero velocity is requested.
   */
  public Command stopWithXCmd() {
    return this.run(
        () -> {
          Rotation2d[] headings = new Rotation2d[4];
          for (int i = 0; i < modules.length; i++) {
            headings[i] = getModuleTranslations()[i].getAngle();
          }
          kinematics.resetHeadings(headings);
          for (int i = 0; i < modules.length; i++) {
            modules[i].runSetpoint(new SwerveModuleState(0.0, headings[i]));
          }
        });
  }

  public Command runChoreoTraj(ChoreoTrajectory traj) {
    return this.runChoreoTraj(traj, false);
  }

  public Command runChoreoTraj(ChoreoTrajectory traj, boolean resetPose) {
    return choreoFullFollowSwerveCommand(
            traj,
            this::getPose,
            Choreo.choreoSwerveController(
                new PIDController(1.5, 0.0, 0.0),
                new PIDController(1.5, 0.0, 0.0),
                new PIDController(3.0, 0.0, 0.0)),
            (ChassisSpeeds speeds) -> this.runVelocity(speeds),
            () -> {
              Optional<Alliance> alliance = DriverStation.getAlliance();
              return alliance.isPresent() && alliance.get() == Alliance.Red;
            },
            this)
        .beforeStarting(
            Commands.runOnce(
                    () -> {
                      if (DriverStation.getAlliance().isPresent()
                          && DriverStation.getAlliance().get().equals(Alliance.Red)) {
                        setPose(traj.getInitialState().flipped().getPose());
                      } else {
                        setPose(traj.getInitialPose());
                      }
                    })
                .onlyIf(() -> resetPose));
  }

  /**
   * Create a command to follow a Choreo path.
   *
   * @param trajectory The trajectory to follow. Use Choreo.getTrajectory(String trajName) to load
   *     this from the deploy directory.
   * @param poseSupplier A function that returns the current field-relative pose of the robot.
   * @param controller A ChoreoControlFunction to follow the current trajectory state. Use
   *     ChoreoCommands.choreoSwerveController(PIDController xController, PIDController yController,
   *     PIDController rotationController) to create one using PID controllers for each degree of
   *     freedom. You can also pass in a function with the signature (Pose2d currentPose,
   *     ChoreoTrajectoryState referenceState) -&gt; ChassisSpeeds to implement a custom follower
   *     (i.e. for logging).
   * @param outputChassisSpeeds A function that consumes the target robot-relative chassis speeds
   *     and commands them to the robot.
   * @param mirrorTrajectory If this returns true, the path will be mirrored to the opposite side,
   *     while keeping the same coordinate system origin. This will be called every loop during the
   *     command.
   * @param requirements The subsystem(s) to require, typically your drive subsystem only.
   * @return A command that follows a Choreo path.
   */
  public Command choreoFullFollowSwerveCommand(
      ChoreoTrajectory trajectory,
      Supplier<Pose2d> poseSupplier,
      ChoreoControlFunction controller,
      Consumer<ChassisSpeeds> outputChassisSpeeds,
      BooleanSupplier mirrorTrajectory,
      Subsystem... requirements) {
    var timer = new Timer();
    return new FunctionalCommand(
        () -> {
          timer.restart();
          if (Robot.mode != RobotMode.REAL) {
            Logger.recordOutput(
                "Choreo/Active Traj",
                (mirrorTrajectory.getAsBoolean() ? trajectory.flipped() : trajectory).getPoses());
          }
        },
        () -> {
          Logger.recordOutput(
              "Choreo/Target Pose",
              trajectory.sample(timer.get(), mirrorTrajectory.getAsBoolean()).getPose());
          Logger.recordOutput(
              "Choreo/Target Speeds",
              trajectory.sample(timer.get(), mirrorTrajectory.getAsBoolean()).getChassisSpeeds());
          outputChassisSpeeds.accept(
              controller.apply(
                  poseSupplier.get(),
                  trajectory.sample(timer.get(), mirrorTrajectory.getAsBoolean())));
        },
        (interrupted) -> {
          timer.stop();
          // if (interrupted) {
          outputChassisSpeeds.accept(new ChassisSpeeds());
          // } else {
          // outputChassisSpeeds.accept(trajectory.getFinalState().getChassisSpeeds());
          // }
        },
        () -> {
          var finalPose =
              mirrorTrajectory.getAsBoolean()
                  ? trajectory.getFinalState().flipped().getPose()
                  : trajectory.getFinalState().getPose();
          var vel = getVelocity();
          Logger.recordOutput("Swerve/Current Traj End Pose", finalPose);
          return timer.hasElapsed(trajectory.getTotalTime())
              && (MathUtil.isNear(finalPose.getX(), poseSupplier.get().getX(), 0.4)
                  && MathUtil.isNear(finalPose.getY(), poseSupplier.get().getY(), 0.4)
                  && Math.abs(
                          (poseSupplier.get().getRotation().getDegrees()
                                  - finalPose.getRotation().getDegrees())
                              % 360)
                      < 20.0)
              && MathUtil.isNear(
                  0.0,
                  vel.vxMetersPerSecond * vel.vxMetersPerSecond
                      + vel.vyMetersPerSecond * vel.vyMetersPerSecond,
                  0.75);
        },
        requirements);
  }

  /** Runs forwards at the commanded voltage. */
  private void runDriveCharacterizationVolts(double volts) {
    Arrays.stream(modules).forEach((mod) -> mod.runDriveCharacterization(volts));
  }

  /** Returns the average drive velocity in radians/sec. */
  public double getCharacterizationVelocity() {
    double driveVelocityAverage = 0.0;
    for (var module : modules) {
      driveVelocityAverage += module.getCharacterizationVelocity();
    }
    return driveVelocityAverage / 4.0;
  }

  /** Returns the module states (turn angles and drive velocitoes) for all of the modules. */
  @AutoLogOutput(key = "SwerveStates/Measured")
  private SwerveModuleState[] getModuleStates() {
    SwerveModuleState[] states =
        Arrays.stream(modules).map(Module::getState).toArray(SwerveModuleState[]::new);
    return states;
  }

  @AutoLogOutput(key = "Odometry/Velocity")
  public ChassisSpeeds getVelocity() {
    var speeds =
        ChassisSpeeds.fromRobotRelativeSpeeds(
            kinematics.toChassisSpeeds(
                Arrays.stream(modules).map((m) -> m.getState()).toArray(SwerveModuleState[]::new)),
            getRotation());
    return new ChassisSpeeds(
        speeds.vxMetersPerSecond, speeds.vyMetersPerSecond, speeds.omegaRadiansPerSecond);
  }

  @AutoLogOutput(key = "Odometry/RobotRelativeVelocity")
  public ChassisSpeeds getRobotRelativeSpeeds() {
    ChassisSpeeds speeds =
        kinematics.toChassisSpeeds(
            (SwerveModuleState[])
                Arrays.stream(modules).map((m) -> m.getState()).toArray(SwerveModuleState[]::new));
    return new ChassisSpeeds(
        -speeds.vxMetersPerSecond, -speeds.vyMetersPerSecond, speeds.omegaRadiansPerSecond);
  }

  /** Returns the current odometry pose. */
  @AutoLogOutput(key = "Odometry/Robot")
  public Pose2d getPose() {
    return estimator.getEstimatedPosition();
  }

  public Pose3d getPose3d() {
    return new Pose3d(getPose());
  }

  /** Returns the current odometry rotation. */
  public Rotation2d getRotation() {
    return getPose().getRotation();
  }

  /** Sets the current odometry pose. */
  public void setPose(Pose2d pose) {
    try {
      estimator.resetPosition(lastGyroRotation, getModulePositions(), pose);
    } catch (Exception e) {
      System.out.println(
          "odo reset sad :( (this is likely because we havent had an odometry update yet or are having odo issues)");
      System.out.println(e.getMessage());
    }
  }

  public void setYaw(Rotation2d yaw) {
    // gyroIO.setYaw(yaw);
    setPose(new Pose2d(getPose().getTranslation(), yaw));
  }

  /** Returns an array of module translations. */
  public static Translation2d[] getModuleTranslations() {
    return new Translation2d[] {
      new Translation2d(TRACK_WIDTH_X / 2.0, TRACK_WIDTH_Y / 2.0),
      new Translation2d(TRACK_WIDTH_X / 2.0, -TRACK_WIDTH_Y / 2.0),
      new Translation2d(-TRACK_WIDTH_X / 2.0, TRACK_WIDTH_Y / 2.0),
      new Translation2d(-TRACK_WIDTH_X / 2.0, -TRACK_WIDTH_Y / 2.0)
    };
  }

  public static VisionConstants[] getCameraConstants() {
    return new VisionConstants[] {rightCamConstants};
  }

  /**
   * Returns the module positions (turn angles and drive velocities) for all of the modules without
   * PhoenixOdometryThread.
   */
  private SwerveModulePosition[] getModulePositions() {
    return Arrays.stream(modules).map(Module::getPosition).toArray(SwerveModulePosition[]::new);
  }

  public Rotation2d getInstantRotationToTranslation(Pose2d translation, Pose2d pose) {
    double angle = Math.atan2(translation.getY() - pose.getY(), translation.getX() - pose.getX());
    return Rotation2d.fromRadians(angle);
  }

  public Rotation2d getLinearFutureRotationToTranslation(
      Pose2d translation, ChassisSpeeds speedsFieldRelative) {
    double angle =
        Math.atan2(
            translation.getY()
                - getLinearFuturePose(AutoAimStates.lookaheadTime, speedsFieldRelative).getY(),
            translation.getX()
                - getLinearFuturePose(AutoAimStates.lookaheadTime, speedsFieldRelative).getX());
    return Rotation2d.fromRadians(angle);
  }

  /**
   * Gets the pose at some time in the future, assuming constant velocity
   *
   * @param speedsFieldRelative the field relative speed to calculate from
   * @param time time in seconds
   * @return The future pose
   */
  public Pose2d getLinearFuturePose(double time, ChassisSpeeds speedsFieldRelative) {
    ChassisSpeeds speedsRobotRelative =
        ChassisSpeeds.fromFieldRelativeSpeeds(speedsFieldRelative, getRotation());
    return getPose()
        .plus(
            new Transform2d(
                speedsRobotRelative.vxMetersPerSecond * time,
                speedsRobotRelative.vyMetersPerSecond * time,
                new Rotation2d() // Rotation2d.fromRadians(speedsRobotRelative.omegaRadiansPerSecond
                // * time)
                ));
  }

  /**
   * Gets the pose at some time in the future, assuming constant velocity and uses robot's current
   * speeed
   *
   * @param time time in seconds
   * @return The future pose
   */
  public Pose2d getLinearFuturePose(double time) {
    return getLinearFuturePose(time, getVelocity());
  }

  /**
   * Gets the pose at some time in the future, assuming constant velocity Uses fixed lookahead time
   * specified in AutoAim.java
   *
   * @return The future pose
   */
  @AutoLogOutput(key = "AutoAim/FuturePose")
  public Pose2d getLinearFuturePose() {
    return getLinearFuturePose(getLookaheadTime());
  }

  @AutoLogOutput(key = "AutoAim/Lookahead")
  public double getLookaheadTime() {
    return AutoAim.LOOKAHEAD_TIME_SECONDS
        + Math.abs(
            getInstantRotationToTranslation(FieldConstants.getSpeaker(), getPose())
                .minus(getPose().getRotation())
                .minus(Rotation2d.fromDegrees(180.0))
                .getRotations());
  }

  @AutoLogOutput(key = "AutoAim/Distance to Target")
  public double getDistanceToSpeaker() {
    return this.estimator
        .getEstimatedPosition()
        .minus(FieldConstants.getSpeaker())
        .getTranslation()
        .getNorm();
  }

  /**
   * Faces the robot towards a translation on the field Keeps the robot in a linear drive motion for
   * time seconds while rotating
   *
   * @param xMetersPerSecond Requested X velocity
   * @param yMetersPerSecond Requested Y velocity
   * @param time Time in the future to point from
   * @return A command reference that rotates the robot to a computed rotation
   */
  public Command teleopAimAtVirtualTargetCmd(
      DoubleSupplier xMetersPerSecond, DoubleSupplier yMetersPerSecond, double time) {
    ProfiledPIDController headingController =
        // assume we can accelerate to max in 2/3 of a second
        new ProfiledPIDController(
            0.5, 0.0, 0.0, new Constraints(MAX_ANGULAR_SPEED / 2, MAX_ANGULAR_SPEED));
    headingController.enableContinuousInput(-Math.PI, Math.PI);
    ProfiledPIDController vxController =
        new ProfiledPIDController(
            0.5, 0.0, 0.0, new Constraints(MAX_AUTOAIM_SPEED, MAX_AUTOAIM_SPEED));
    ProfiledPIDController vyController =
        new ProfiledPIDController(
            0.5, 0.0, 0.0, new Constraints(MAX_AUTOAIM_SPEED, MAX_AUTOAIM_SPEED));
    return Commands.sequence(
        this.runVelocityFieldRelative(
                () -> {
                  double feedbackOutput =
                      headingController.calculate(
                          getPose().getRotation().getRadians(),
                          AutoAimStates.endingPose
                              .getTranslation()
                              .minus(AutoAimStates.virtualTarget.getTranslation())
                              .getAngle()
                              .getRadians());
                  double vxFeedbackOutput =
                      vxController.calculate(getPose().getX(), AutoAimStates.endingPose.getX());
                  double vyFeedbackOutput =
                      vyController.calculate(getPose().getY(), AutoAimStates.endingPose.getY());
                  Logger.recordOutput(
                      "AutoAim/Setpoint Rotation", headingController.getSetpoint().position);
                  Logger.recordOutput(
                      "AutoAim/Setpoint Velocity", headingController.getSetpoint().velocity);
                  Logger.recordOutput(
                      "AutoAim/Goal Rotation", headingController.getGoal().position);
                  Logger.recordOutput(
                      "AutoAim/Goal Velocity", headingController.getGoal().velocity);
                  Logger.recordOutput(
                      "AutoAim/Setpoint X Position", vxController.getSetpoint().position);
                  Logger.recordOutput(
                      "AutoAim/Setpoint X Velocity", vxController.getSetpoint().velocity);
                  Logger.recordOutput("AutoAim/Goal X Position", vxController.getGoal().position);
                  Logger.recordOutput("AutoAim/Goal X Velocity", vxController.getGoal().velocity);

                  Logger.recordOutput(
                      "AutoAim/Setpoint Y Positon", vyController.getSetpoint().position);
                  Logger.recordOutput(
                      "AutoAim/Setpoint Y Velocity", vyController.getSetpoint().velocity);
                  Logger.recordOutput("AutoAim/Goal Y Position", vyController.getGoal().position);
                  Logger.recordOutput("AutoAim/Goal Y Velosity", vyController.getGoal().velocity);
                  Logger.recordOutput(
                      "AutoAim/Setpoint pose",
                      new Pose2d(
                          vxController.getSetpoint().position,
                          vyController.getSetpoint().position,
                          Rotation2d.fromRadians(headingController.getSetpoint().position)));
                  return new ChassisSpeeds(
                      vxFeedbackOutput + vxController.getSetpoint().velocity,
                      vyFeedbackOutput + vyController.getSetpoint().velocity,
                      feedbackOutput + headingController.getSetpoint().velocity);
                })
            .beforeStarting(
                () -> {
                  vxController.setConstraints(
                      new Constraints(xMetersPerSecond.getAsDouble(), MAX_AUTOAIM_SPEED));
                  vyController.setConstraints(
                      new Constraints(yMetersPerSecond.getAsDouble(), MAX_AUTOAIM_SPEED));
                  Logger.recordOutput("AutoAim/Ending Pose", AutoAimStates.endingPose);
                  headingController.reset(
                      new State(
                          getPose().getRotation().getRadians(),
                          getVelocity().omegaRadiansPerSecond));
                  vxController.reset(new State(getPose().getX(), getVelocity().vxMetersPerSecond));
                  vyController.reset(new State(getPose().getY(), getVelocity().vyMetersPerSecond));
                }));
  }

  /**
   * Faces the robot towards a translation on the field Uses a constant lookahead time specified in
   * AutoAim.java
   *
   * @param xMetersPerSecond Requested X velocity
   * @param yMetersPerSecond Requested Y velocity
   * @return A command refrence that rotates the robot to a computed rotation
   */
  public Command teleopAimAtVirtualTargetCmd(
      DoubleSupplier xMetersPerSecond, DoubleSupplier yMetersPerSecond) {
    return teleopAimAtVirtualTargetCmd(xMetersPerSecond, yMetersPerSecond, getLookaheadTime());
  }

  public Command runModuleSteerCharacterizationCmd() {
    return Commands.sequence(
        this.runOnce(() -> SignalLogger.start()),
        moduleSteerRoutine.quasistatic(Direction.kForward),
        this.stopCmd().withTimeout(1.0),
        moduleSteerRoutine.quasistatic(Direction.kReverse),
        this.stopCmd().withTimeout(1.0),
        moduleSteerRoutine.dynamic(Direction.kForward),
        this.stopCmd().withTimeout(1.0),
        moduleSteerRoutine.dynamic(Direction.kReverse),
        this.runOnce(() -> SignalLogger.stop()));
  }

  public Command runDriveCharacterizationCmd() {
    return Commands.sequence(
        this.runOnce(() -> SignalLogger.start()),
        driveRoutine.quasistatic(Direction.kForward),
        this.stopCmd().withTimeout(1.0),
        driveRoutine.quasistatic(Direction.kReverse),
        this.stopCmd().withTimeout(1.0),
        driveRoutine.dynamic(Direction.kForward),
        this.stopCmd().withTimeout(1.0),
        driveRoutine.dynamic(Direction.kReverse),
        this.runOnce(() -> SignalLogger.stop()));
  }
}
