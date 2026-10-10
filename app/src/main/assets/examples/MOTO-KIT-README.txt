This folder contains ready-to-use patches for updating the "Motor Fader
Performance Sequencer" on your Moto Kit or Moto Kit 2.

The "Moto Kit" was sold until 2025. It contains a MASTER18, P2B8, B32
and 2x M4.

The "Moto Kit 2" is sold from 2026. It contains a DB8E instead of the
P2B8 (MASTER18, DB8E, B32, 2x M4).

To update the sequencer on your kit, do as follows:

1. Switch off your Eurorack rack.

2. Take the small micro SD card from your MASTER18 and put it into a reader
   in your PC or Mac.

3. Choose motokit.zip for the "Moto Kit" and motokit2.zip for the "Moto Kit 2"
   and copy the files droid.ini and master18.fw from this ZIP file to the SD card.
   Don't forget to correctly eject afterwards.

4. Put the SD card back into your MASTER18.

5. Switch on your rack.

6. Wait until the sequencer is up and running and press the button on
   the MASTER18 to load the updated MFPS patch.

7. If the sequencer is not running, you can hold the CTRL button for
   2 seconds. This will do a factory reset but erase all your sequences
   and presets if you had any.

Notes:

- This procedure will update the firmware of your MASTER18 to the newest
  version and load the new version of the the Motor Fader Performance Sequencer
  (default preset).

- The files "motokit.ini" and "motokit2.ini" cannot be loaded onto the master
  directly since they are too large. But you can open them with the DROID Forge
  or with any text editor, if you want to learn how the patch is built. The patches
  in the ZIP files are highly compressed and not readable for humans.

- Instead of using the patches in this folder, you can recreate them with
  the patch generator "Motor Fader Performance Sequencer" in the DROID Forge.
  This is also the place to go if you want to modify the sequencer's configuration.
