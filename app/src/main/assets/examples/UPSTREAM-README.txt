Dear DROID user,

this package contains the current firmware for the DROID modules and all
sorts of other useful things, which help you when you work with the DROID.
The DROID Forge is not contained in this package. You find it on our
download page on shop.dermannmitdermaschine.de. Please make sure that
you use DROID Forge 1.7 together with this firmware release.

README.txt:
    This file :)

firmware:
    In this directory there are the firmware files in case you want to update
    your MASTER, MASTER18, X7, E4, M4 and DB8E. Please refer to the manual
    on how the firmare upgrades are done. Check the release notes (on our
    downloads page) for information which firmware has changed and needs to
    be updated. In most cases there is no need for an update of your controllers
    when a new release for the master modules published.

manual:
    Here you find the comprehensive manual of all and everything about the
    DROID, a cheat sheet which just lists all the jacks and circuits as a short
    reference, and last but not least a file with all example patch snippets
    from the manual for easy copy & paste into your own patches. The manual
    is also contained in the DROID Forge.

patches:
    A collection of example patches. Each of the patches is built upon a certain
    configuration of controllers. The controller declarations are on top of the
    patches. The patches will only work if you have (at least) those controllers
    attached to your master, in that order. I need to apologize for the fact
    that some of these patches are rather old.

    The patches with the prefix dmmdm_ are the current versions of those
    used in Der Mann mit der Maschine himselve's live instrument. They are
    rather complex but you might get some inspiration from it.

motokit:
    The default patches for the Moto Kit and Moto Kit 2 (Motor Fader
    Performance Sequencer). Here you find everything for updating your Kit
    to the neweset version without having to use the DROID Forge.

graphics:
    Here you find two subdirectories with PNG images of the face plates of all
    Droid modules and of the circuit icons from the Forge and from the manual.
    You are allowed to use these images for private projects and for those that
    you release as open source projects under GNU GPL or similar licenses. Other
    use is restricted. Please contact me if you are unsure.

utilities:
    Various mixed utility files.

    In the subdirectory "editors" you find support files for various editors.
    The file droid.vim adds a syntax hiliting for DROID patches for the text
    editor vim. If you are using vim, copy that file to your home directory into
    the sub directory .vim/syntax. There are also circuit snippets (code
    templates) for Visual Studio Code and Sublime.

    The file circuits.json is for developers who want to create some sort of
    syntax support for other browers. It contains a database of all circuits and
    jacks of the current firmware version, ready to be used by some script.

    The subdirectory "sysex" contains one subdirectory for each of Windows, Mac
    and Linux. In the Windows directory you find the files sysexhead.txt and
    sysextail.txt for creating sysex files on Windows (see the manual for
    details). Both the Mac and Linux directory contain the script "droidpatch"
    which will upload a patch via your X7. The Mac directory also contains a
    version of sendmidi that can be installed into /usr/local/bin and is used by
    droidpatch.
