# Commands in Forge PC and the Android port

Comparison with Forge 1.7.1, blue-7 firmware, based on
`upstream/droidforge/droidforge/main/mainwindow.cpp` at commit
`60cbb7bbf9630c7d4a184e346a3d1120bde4d69f`.

The table lists the commands available in the port and PC features still to be
ported. The mobile toolbar buttons run the operations named on them.

| Area | Available in the port | Still to be ported |
| --- | --- | --- |
| Files | New patch, `.ini` import/export, source editing, private autosave, catalogue of 630 official examples | Full PC library, additional compression options, Python generators, integrating another patch, exporting a selection/section, recent patches and multiple windows |
| Circuits and parameters | Add/delete circuits, add parameters, arithmetic input cells, circuit comments, raw editing, catalogue help, initial parameters, undo/redo | Cut/copy/paste, duplicate and move a circuit, enable/disable a block, sort and expand parameters, delete a parameter line through its menu |
| Cables | Start/finish/cancel a connection, choose/create/rename/follow, confirmed merge, clear a cell | Bulk rewriting of cable names |
| Organization | Section navigation, circuit folding, preservation of imported comments and separators, catalogue search | Structured section editing, bookmarks, parameter comments, patch search, following a register/circuit with the same name/same parameter |
| Rack | Official panels, controller details, labels, double-tap to add a module, MASTER16/18 recognition, G8/X7 inferred from parameters as on PC | Duplicate/move modules, structured MASTER type selection |
| Display | Portrait/landscape and narrow layouts, rack zoom from 50 to 400 %, rack panning, English application interface | Circuit zoom and all PC display options, mirroring, forcing a minimum number of G8/X7 modules |
| DROID | Local diagnostics, DROID text preparation, Activate! MIDI/SysEx through X7, Save to SD with backup and read-back, droid.ini import, recovery of originals, Eject SD through Android | Complete validation/allocation and RAM analysis, live monitoring, hardware firmware reading/updating, reading the active patch from RAM |
| Information | Installed version, supported catalogue firmware, credits and licences | — |
| Help | Catalogue information and official examples | Full or circuit/module-specific manual reader/links, videos and Discord |

The ten original toolbar actions have mobile equivalents: New, Open, Save,
Circuit, Parameter, Module, Problems, **Activate!**, **Save to SD** and
**Eject SD**. Ejection depends on Android permissions: when the application
cannot request it directly, it opens DocumentsUI so the volume's eject icon can
be used. Returning from the picker does not confirm that the card was ejected.

**New** creates an empty MASTER18 patch; the PC keeps its rack. **Save** opens
Android export without directly overwriting the previously imported file.
**Save to SD** writes a copy prepared for DROID; the editor document keeps its
annotations.

See [DROID connection](DROID-CONNECTION.md) and [0.5.0 validation](VALIDATION-0.5.0.md)
for X7 modes and completed tests. Successful local analysis does not establish
that a patch works on a physical DROID system.
