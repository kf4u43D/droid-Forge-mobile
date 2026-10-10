#!/usr/bin/env python3
"""Import unchanged DROID Forge toolbar and cable PNGs from the pinned GPL v3 source.

Uses Git objects, not the checkout's working files, and only Python's standard
library. Import from the existing upstream checkout with no network access:

    python tools/import_forge_icons.py
    python tools/import_forge_icons.py --verify

If the checkout is absent, clone https://github.com/Zarkuun/droidforge.git into
upstream/droidforge first, or pass its location with --upstream.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "https://github.com/Zarkuun/droidforge"
COMMIT = "60cbb7bbf9630c7d4a184e346a3d1120bde4d69f"
MANIFEST = ROOT / "resources/official/forge-icons-manifest.json"
ICON_PREFIX = "droidforge/images/icons/dark/"
# These hashes pin the exact binary assets and legal notices independently of
# whichever branch or working-tree modifications exist in the source checkout.
ICON_HASHES = {
    "new.png": "6a158d0245328eba05310f7e34c4c690a60f80be63a6fa90ac65a5a9cac63e99",
    "open.png": "f2e582b18de802fe3104661075b7737f25a40fd75347cd50a85aeda2b81c2e02",
    "save.png": "66b64af5f61b2942fe1f41317dc2363274ca4d7d8db3cd46a586a731c6a031ca",
    "new_circuit.png": "9bc0edae9b372ec39d55f370a90856154a1ddd73d1040eef10cf20335b94c7cd",
    "add_jack.png": "91c8e3a2eaf109a1e8e6a02faa2ced4d915d1c5cbd92e83bad3bb6ffa33f183b",
    "add_controller.png": "b531d46218e12e156866e60516034e116cf711ae6529c457206019cb06f6b68e",
    "problems.png": "8e9635bf2e2f236fc6a9f3b741fbf80b630ffd1875ff3a346a9d787d66e2c788",
    "info.png": "36e3bc718b85b406300a4e73d526b44f16551297ced43d2e2ff8867e31dcc8a5",
    "folded.png": "f3b2eb7c84a50a64e71b5bfbb0edcc86a0f0a42857d8e3c3935b8b1ac885cef1",
}
CABLE_HASHES = {
    "cableicons/black-0.png": "9d1251be36b3e23f0106863c9233e4aa3975257e079f8d7b3e5ec9e37059646c",
    "cableicons/black-1.png": "de549279d18fedd4ecbfdb4200cfe82666a2cef1fbb59126d3ca1dc370e6dd05",
    "cableicons/black-2.png": "4d4c57c4a0d47fd2d28f8d1382b21f2536570c088cc359f274d290ffd60d949f",
    "cableicons/black-3.png": "1c5167be58b1e79c0c4aaf78ca327c88754a584d2e79650acb09b9f96f35ca4e",
    "cableicons/black-4.png": "a71f16490434ed249ae09d200bcf87bf9e2ca2ff96b74b4ddfdca9bae740b895",
    "cableicons/black-5.png": "4e2a8489cfc749db268db523256693626840a56b57bdd3a1be2a246dc0429fd4",
    "cableicons/black-6.png": "154d6f38c6b9298f2f9abea30abdd106e16145dcba155e3a2c014c7e52ba3888",
    "cableicons/black-7.png": "b5aabaf8df537f215dd89d4efa92ebd0a89c08a049cc1abb77781fd2b457967b",
    "cableicons/black-8.png": "33912e1f0dc8bcf7abf1837470b6928c0f6223586a23de221774953f979c2cb8",
    "cableicons/blue-0.png": "199be336cce616aec9ff4c6ed3e93a5eebb298c1ca8747b9e40944c899a202e8",
    "cableicons/blue-1.png": "9f1d8ac21d9c1576584ad982df16608886a7e45b1c8edd9bd2e39f936e366ef4",
    "cableicons/blue-2.png": "f47ff0c5837204b01a1d9a1c0a25a14cbe82a0c59586d8dc2c633049a2f5684d",
    "cableicons/blue-3.png": "698b357207a3447cb402d64c923744ef99e94665fe1319920ffeb5c30cee06b1",
    "cableicons/blue-4.png": "e3401b60a366c42dd4c6b28692ad51155e5b8e918db1215dc5a6eb902933ea2f",
    "cableicons/blue-5.png": "e96f305424cdd90e701f13550c53b577113f090127cfc7561b3bb1a90540b29a",
    "cableicons/blue-6.png": "b895a8301480e8c1e5e69f212326467276f87ee811f97b89f3a3279e9172e09a",
    "cableicons/blue-7.png": "e5f0b83c8f5b859c8d844d0d2e7ec139e94a77e0a0f6d594678aa4266f42f4ac",
    "cableicons/blue-8.png": "e59d8e0e6171c752bb6e564affd04b155923b1cc5e0a18e550815280c24450bd",
    "cableicons/brown-0.png": "7f4d8c931485d20ef95d49d58a825fa9665d20a828dd0cb1c1dce85719b979ca",
    "cableicons/brown-1.png": "343a958f4ab0d281d2899e735ef480902d4321a0cdefc84750ebf666b29f4f5c",
    "cableicons/brown-2.png": "80bf1c11343de2f1d0ad7766247ccd640be5a5922010870f54e2ccbd87cd6c2b",
    "cableicons/brown-3.png": "5fa4330293ae9132b9ff7c22030146c6a9ed6a366784d599903d7b4a7677fc26",
    "cableicons/brown-4.png": "a78a84b499feee98de75d1cfebc5212d0ab2a4387e1f52b2e92e01ec0b5ef9ec",
    "cableicons/brown-5.png": "b65f2c5f171a37ac7124c479d44e8b391d9942436b45a479eba9176f2dbc4d6a",
    "cableicons/brown-6.png": "3640884ee35782a261cdaf48b9c8acae0f2ce12577942cd8b310c2f98abcea8e",
    "cableicons/brown-7.png": "e999da4f2adbd4da32a3a55db66748b9512543d46cb26abc6fc7a6d85d4d7cc5",
    "cableicons/brown-8.png": "8227d1cd6cc969bb1947a7edd5eb8c38c489d83559dfa45cd06064bbb2a59f36",
    "cableicons/cyan-0.png": "98a5a8daf859c90e5404ebf9be475c33a2cbf6dc476e050f8ea585ea20368e06",
    "cableicons/cyan-1.png": "7b8611dd550594a480ddd4b72a69742d8ab717b2426150fe49452b59ba66aa2b",
    "cableicons/cyan-2.png": "0feae9beef7bbc8fc30898136e8797bed434ce34f8a7829ae8f7089e76eb0d47",
    "cableicons/cyan-3.png": "789e1471951289f50b9c39ed2d69e85bcd73dab25736011d4c69ddb2b66655d0",
    "cableicons/cyan-4.png": "1208ff21e76de21e492a49227819fb578c5dbcf64607ed86bf3fd0da17d9a928",
    "cableicons/cyan-5.png": "d7cfc1582aa53902289a5f2eda020cee3d3cad89a8f4ad0d58d0374b95d9fa46",
    "cableicons/cyan-6.png": "78dc945434507a30b4e88620aba50932ea25c657080cdfe2161eb07ed81b7836",
    "cableicons/cyan-7.png": "76c60ce233caaef0f683dff4100a9cb522f846130ec1436dcd1e41b81c9bce10",
    "cableicons/cyan-8.png": "3139431555ff8e98f9210894214fc2d9319b5d65030c67f9fa9929564ad4ee6c",
    "cableicons/darkgreen-0.png": "4063a940be41265c28984d902e4ccc524d7b5c987e4f29edfd74fa075a57ef28",
    "cableicons/darkgreen-1.png": "4fcd9fb8800f5a2f5636bb6d47f772de981ad5f905c4713b197429f03d60044c",
    "cableicons/darkgreen-2.png": "f03c5f1b4076a4f0c38aede34379050e3916aca0daf349a378e2b9563647c83d",
    "cableicons/darkgreen-3.png": "2295a144c5693a9340b0e9552d835fc1bf6f70aebe1aca7ce785927fc026c26d",
    "cableicons/darkgreen-4.png": "b067361ec9bf08920c86ac4805bc988ee8ef7c9a4fa203418a966e5944370253",
    "cableicons/darkgreen-5.png": "ee7977bb59e32cdf63cd0dabc300b99b9a3c3c434eab2fc9802a68266e77a72f",
    "cableicons/darkgreen-6.png": "c2e10c7fc591fffaca635277004ea087a89b450e0c33f1bc7b9b38ea58fd8028",
    "cableicons/darkgreen-7.png": "6e6ede35f44cb9d4bf1613773b10537aa34298ede3ed9bc787f540b54d6d3e22",
    "cableicons/darkgreen-8.png": "0cfff0ed5a9cbc8988d6244c49f1351ff65ff5ed6a4084d06336f6bbe4abe2f4",
    "cableicons/ghost.png": "b76ca896e3b523b11f1fb00093e59f9c9785dae7dc9b1e39bf2477a2bb9108b5",
    "cableicons/green-0.png": "6a1b1f404c7c24303a0eb746d097dc93eaf54b1f266fa3ef838b2db61b30cab4",
    "cableicons/green-1.png": "d0438d4ac67479b273890631c17907c3b3fb5905291688b88e0659b358af9dfd",
    "cableicons/green-2.png": "f8aabc2a4f401c2843f8bb14b135cd0870d3d601dab1c3d66319d9817ee74cd1",
    "cableicons/green-3.png": "e830299bd17b8ec878de4d5170abdb9c9c7bb4672a0d44b512533d90c4fefd9d",
    "cableicons/green-4.png": "874745f914cd988b34a7269170f50c8b217779b0bf680c9a682e4520fde98bc0",
    "cableicons/green-5.png": "3b575584a46df459ff11b743a8db5909b2c6dffd561e1e62d97c4ffda0657195",
    "cableicons/green-6.png": "0953930519d4cd88a4fa18c139796ee4e2cc59c96e63eca97506bc56f9b13a42",
    "cableicons/green-7.png": "a2d1dda659568a36906bfe62c9348c9d61c734d006a32baaaaeaa727fd449f3d",
    "cableicons/green-8.png": "8f5c925733423189ffef2e24ebac4dd7152381c3a754598aa06854e6977fdaf6",
    "cableicons/grey-0.png": "4c174ee2deef976d884dd2b327e3aa8f6db6af8d2c2cfea6bca4d03933b69dab",
    "cableicons/grey-1.png": "7df225e6b0c0551c192810a6072b4504f57258056e0144a2b712ac86b902dcb3",
    "cableicons/grey-2.png": "35d5116c7dc60ada99ab90fd0d2fc8c7b3c517d2ed744cb27a5510936a54d823",
    "cableicons/grey-3.png": "b57a97ea346876c25a3f74736c6d1ad76c4624c9b022580c50140c79fba30f93",
    "cableicons/grey-4.png": "bb208d2dcc50dbed4eb16a0b682cb37805193f8341965bf69b002eacee552956",
    "cableicons/grey-5.png": "cdcb2d5f5c2c70be31489bd800b42bc4c244e81a1f05d6e3c9ad04cd9b377aa1",
    "cableicons/grey-6.png": "8244ce8ebe10f9361022b907c00d3eb6d5ed9d514e7b75f257cd8c40e4b7bef2",
    "cableicons/grey-7.png": "5cdbea3d1e556a28204647ef3007be6a4aa3d46e55798fc1f6653dbe0a1ba2c7",
    "cableicons/grey-8.png": "4a826869b9d1916827ae8e420abda939abdd753bff3d92ab10cb0ea5368959bc",
    "cableicons/magenta-0.png": "0c4aa693fb283cb47368a8c2b965280bf6200defb45cdc8774f15caac1bf26e5",
    "cableicons/magenta-1.png": "fa3d66d4c9331d100bf184c0768ac64f0530abfe9dd755e5e921639413ba5312",
    "cableicons/magenta-2.png": "4ec1c471201698da3f32929cc76fb997139593fa90ab4c206ad2a1e00eb53620",
    "cableicons/magenta-3.png": "6da09f0f7299e37f2471d2ac54ced1a47fa0aade798cb76b4bf24f8b21c13c16",
    "cableicons/magenta-4.png": "a550689534c7d443c071a9f82d9dba1c8393f7b19bb7e01ea6f6aa1b46b583e9",
    "cableicons/magenta-5.png": "62d32d01cb0190e3f6dbd3bf90f980f74a0d1b03afe8a74ad55d330f2de77e43",
    "cableicons/magenta-6.png": "da671cd25e7e45f66d0f2a58aa7e0543e346873d4288675f2f9dc0eef38ad327",
    "cableicons/magenta-7.png": "acbf3111bd081a2eaca8a897e76cda2d56e862d7966fd3bb8f61fdfff4bebe3d",
    "cableicons/magenta-8.png": "391c54f6159f053588c0c3e0330de70a0f4924d2fe319b11e9b55de2ee797b9f",
    "cableicons/orange-0.png": "e3c9f3aa34707e9e09c4d4932c8f6ebfd48dc9fb40866d1de688e1089ebdc95a",
    "cableicons/orange-1.png": "8306a0a1ff3ed77cb8fd7d2e74e5b3872454f822bd1ccd91721ceefa8aa8f942",
    "cableicons/orange-2.png": "b10586e1d8d90a39dd498dd9680b1e7a03ab562ced06b8d3b3807a070a2772b2",
    "cableicons/orange-3.png": "68042c521433e5b476b2e7c11baa51eb14329e99e40eaef8408bb39f89a54802",
    "cableicons/orange-4.png": "4979ec0c834e4660530edc91899bc6267de5dc703873f4b1c6066367fc9d0128",
    "cableicons/orange-5.png": "5cc2130571f7df66c6625e7acd2edaf16cf8452c8e5fe00af50358a55ede7ec1",
    "cableicons/orange-6.png": "efecc017c8fb6d24a1d379e3b68102efbc01931082e22292dd53f01f51598e38",
    "cableicons/orange-7.png": "6df16b533fe718ab43f2c37e84c08e80ed596111ba2bcb148c5fd46007723594",
    "cableicons/orange-8.png": "57e5761e7f93d64b5fd4668edbefdf163a5fb5c25422fbbcefdcec8c00beb8b8",
    "cableicons/red-0.png": "eb4df36ee391ff35310555d8a0492c34538719f066ff39a3cfde6b1de62d2e79",
    "cableicons/red-1.png": "a24481199ba867f4b71081d685d44ddeea2627c6fd4ea7d34a82fd287f03bebd",
    "cableicons/red-2.png": "41321b94377e0093095806663c2e77af5c2da05e88568ef277bcfa182d52e6fb",
    "cableicons/red-3.png": "43f7896da7ae4bd1cdef58f756d46cdb06dd5478f18ea8001e99d94e86adc1f9",
    "cableicons/red-4.png": "76c9ba00774a79bf04f24281a91e54bd8d5e834bfe51f646cda2e0c959fbe4a3",
    "cableicons/red-5.png": "bb8bef408991e3658303102614dcfb2227cf01a3819cd6e34af2b22f6b96b30c",
    "cableicons/red-6.png": "6870200523cbdf2cc68af1ec168d2c63edf4da0e26fab50b6f27c0e7cbe283c1",
    "cableicons/red-7.png": "965b0c915304a7aad3eb345138b554a1ef726ee5aad93fa0e677fe5e33dc9f0d",
    "cableicons/red-8.png": "86c14709d51eb5de293555898e92b18023f77c8dc449f70834236c986f9ec20f",
    "cableicons/white-0.png": "4b60aa19c22e32862c5aee9aa25950989cbb47dde4478322ac99164b3e563ac2",
    "cableicons/white-1.png": "77a869d0dd3430ea079980eb378ccf27dc2a8daa07eafeb4b827e2fc652cd28d",
    "cableicons/white-2.png": "86195d5402fe74d0c416bc9705d77d3cf7e90d8374641ed2c4f4df64d8f299df",
    "cableicons/white-3.png": "903f0e23c44ee1b2f6b63ac1880cc00d9d417471dc8ca6a4b98bf13839e651a9",
    "cableicons/white-4.png": "3e049c523ad4f9cdb58d50a194572d69e30cb22b0a247084c7111e0bfadc7d4a",
    "cableicons/white-5.png": "110f92910462cac34770f9836d272b6901b7279e1c103824683e90556a790ad3",
    "cableicons/white-6.png": "0f779f9a26b230d83928d8fe2824a77f07a53baabbcd5aa5fb2e4426f9292bde",
    "cableicons/white-7.png": "af6759f7cc0f8b543b99390cf52bbc9ceb3d5fde19c6702e7701f151b653da09",
    "cableicons/white-8.png": "0419467aefeea4596022e86eab5fdf0e8b608990e07bcc65c9bcff0a5876298d",
    "cableicons/yellow-0.png": "5746c293de3596243d417660e221fa12ef0f30988e536daf6d51acf3d5c7e1c7",
    "cableicons/yellow-1.png": "e18554dc063585f270a3a41db3eba188881800ea95dc1e50bac3a51aae23526e",
    "cableicons/yellow-2.png": "a6640371edd199ae8c6ebb85034f5c148b5b170a185673faeb036a9bafd24349",
    "cableicons/yellow-3.png": "23120f9b808f6ba090343957a2233a5b948d49c4fbb190e4b16b8ab3c8e93d06",
    "cableicons/yellow-4.png": "6b32d3f4a8db927bcb2dec076d0b754df9161bc6837ecfc791ecfb5d2351072d",
    "cableicons/yellow-5.png": "59a6580546964789e3cce1fd106b69331846cfcf46ad6231b0801047b15e4df3",
    "cableicons/yellow-6.png": "a4bdbd83f3dda8857b5305b6f5959a0a6bcc2fa0c6ecdd80330b958a09c8edc3",
    "cableicons/yellow-7.png": "16df00b4fba12ef38fab8c67833e58ec35cb6461fe39010e5c75f04e82a42e0c",
    "cableicons/yellow-8.png": "881b14b5e7b3617d69190c42dab2450a8a703b47c1202d3201d7b53e01c391ba",
    "cables/black-0.png": "7392dec70b41d9e409714ec80b79bc18f7054913f88a41da7630f614eb66e6e9",
    "cables/black-1.png": "f4c233e21d3c5235c5ea8750d9fa4dc322451e642e9445e3cbf588923d410a75",
    "cables/black-2.png": "b3be95d52c7fe84efa6fcb615c621e36eccfa754177e331e80dcedb4c2b14cb5",
    "cables/black-3.png": "52118d4d1949506ea9af35c0cd43b7f894475c4ab7de5f599876d569050a1599",
    "cables/black-4.png": "9a51b6b29a21aa625a554623a786231cceff3a7fc5bcdae71dd3bf1949c61b8a",
    "cables/black-5.png": "422c2b2c70b800c30357d6b94edefd12de31320bf404febaa3506b9a9a5fb0e1",
    "cables/black-6.png": "9427c2fb48d135bacb0ef3667728e53c3a296e4a84b3754d3b6baa01910eb4bf",
    "cables/black-7.png": "afbc9e99356a12a4e97214e0121eb8900f9db477d86a13b50086b107c993bff3",
    "cables/black-8.png": "800698b3a790927d1233e7d5f052a1c5fbb51d4adf715adf86aea7fa0aef03a6",
    "cables/blue-0.png": "bd77612276e4625b76323340142359215de48dfe3d1d15509bc144a393be3cd3",
    "cables/blue-1.png": "a31b7b6870736d620ebcbddbd485b0dff950495ddfe3ff2ca0639070061ebb28",
    "cables/blue-2.png": "591ace5c8e936bfafd3e01c45c0f9e55dc49d59531a90b1f6320e171f59b4337",
    "cables/blue-3.png": "5c6b4df807121931e1c2060e1ac851b4651c96f9c81e204ecac0d23a413cc143",
    "cables/blue-4.png": "67f13d8aba1abe4af5321bcfc29ca56f77fbd5addc12e4d0f479897ab62e3194",
    "cables/blue-5.png": "c53aa3ae95de87bd130bf7cdbc988505f69ea9a17b8a8d7f9b623bc766672507",
    "cables/blue-6.png": "69d489600c6cdc236c3341a2f16f8a0b017755ef09a54af41b72e19379a6680a",
    "cables/blue-7.png": "b931abc065f57281142eb6bf52b70d5abcc67b80a4d01c6807de04b51443b9e0",
    "cables/blue-8.png": "28a811d463be446737758cddd5475be47221af9d06c89c6d144fb30dc2a19aad",
    "cables/brown-0.png": "4024279d8c16b260f562f964017f1d2b774a5053b06adf6dc0386f156f2843e1",
    "cables/brown-1.png": "68a663a3f9fe26f070a2fff4ac39d2fbe55b9c769c8db5083009b142a7becbbf",
    "cables/brown-2.png": "3af6313af4fbae0117ff951a51e2bfb2587d55c81b73083af06434beaa7b693f",
    "cables/brown-3.png": "1f1e86435293a488bd853e42ec1d9a5a8d0d43c63dcec6205f3ecc8e760a2bd4",
    "cables/brown-4.png": "5006dcbe9ebfcce5053a4cf23f66da7a1f0a0016fbc4ba3d09f659671a737094",
    "cables/brown-5.png": "186702db8208124d62cd1b810b0590b1d8708239192980e45d2829c411407f9f",
    "cables/brown-6.png": "4df564329e06f21db07ab50d373b6e8ab023d37722c60e7b4e36ae1ddeebe297",
    "cables/brown-7.png": "82af7e1be9ebd80303d42890c0ef329cbb70e292be7788374a978027fb05f6a8",
    "cables/brown-8.png": "2537d6f5db70aa96245848c00799d5bf9bb5b51f72891e9a964843fc9be0289e",
    "cables/cyan-0.png": "cfce89b1f95a88c767af4ea5ada4b8933eeea1c26a8a0b54228e59ab64f512ed",
    "cables/cyan-1.png": "5a20cf5f7f84904760bf580ba4b87c61e4fcae29039d8ca8d5aa016f5ac505a7",
    "cables/cyan-2.png": "b5eb2db2f60b86c7e4714aa54c4082f74eb26cb42225ec172614a47273f60454",
    "cables/cyan-3.png": "440383d8b79ea3fd2d10bd8ca8f17e530e58e1107ecd36c36a9dcceec53f7619",
    "cables/cyan-4.png": "75d2d38020cd5459ff1b9b773ec7673a9c4e90a38df8ec4a5397bf27f184bc3e",
    "cables/cyan-5.png": "81e54042cb8ed4e68575472e533727fac18bd85343aeda39fc585234e579b093",
    "cables/cyan-6.png": "8600d0545a2661ef2ecd0e5580f64d89f4851c12bfa757dd843a6fae27ac447d",
    "cables/cyan-7.png": "31558aedffacea7f7ac1295e5d0163edcb60e38590d57d53d1cc9249fc14ecc3",
    "cables/cyan-8.png": "f6922dccf84dd3ed469f79476b4d27bf99b9885ed40ed00439f74a6c6c5fcb69",
    "cables/darkgreen-0.png": "bb38e4c5e7ff814da591a0dabcb6aed5400dbb0a6e3e2584d620832c6aeee078",
    "cables/darkgreen-1.png": "7dd25763139e4ead25c9496022a1bb0896e41682f628a5f99932b266d3202126",
    "cables/darkgreen-2.png": "0ebeaeff4d016550b2a1557a5f1e2af799cbd6df98c0c2835454b288acccd7c5",
    "cables/darkgreen-3.png": "70cc5797a22edc4f82b4ed317284a9da0d327f35ded528d9f9319d715a3f11c7",
    "cables/darkgreen-4.png": "2fe16819af7cffdddf3c94c7abeb98cce1e7bb055a91825ea39ee0327d127755",
    "cables/darkgreen-5.png": "6199c35700dbfdce911495bcdeeedbf12b1e483f8073d7b9224b7b19747d76e3",
    "cables/darkgreen-6.png": "ab1d6be348194242963da195116105cf92028ed3a6ccf98a85a27b2195e62299",
    "cables/darkgreen-7.png": "aa58170f90b09ad51e70c1a379985f5457496a56245244500b22a67225d0eca1",
    "cables/darkgreen-8.png": "0d5180ac5f47241fcda3dc1d98f9ceef2e33a7e3c66e39313b0644246d3d603d",
    "cables/ghost.png": "cfa9d5cfdfda8c4fe7ccbf672e10456b767370cf91ae26b1e04cb3c2a07e6d19",
    "cables/green-0.png": "7e2ef4f0dee78984bb76689f4bd2b8c279441fb1046839d7b09c30006f4285ff",
    "cables/green-1.png": "7f536f82f962d788e898375af8b4b8950876476003fc2e5918cf088eb08bf074",
    "cables/green-2.png": "cd19243c79bb88e22dad9307632ee5afb7d3118619eb20b166aec4a4f8237a4c",
    "cables/green-3.png": "9eee6812e0d278e30bc6e624ccec55166853b2ed38113dd4ec6708b30c5597ed",
    "cables/green-4.png": "5390d6749c187d4530fc8a3537290cf1a257bd6d6e82e273a21c64265f486235",
    "cables/green-5.png": "e9f1e0ff41b50558aea3d0a8274f86af58ad195d73608d58110d364e7fe46e2c",
    "cables/green-6.png": "e75fcb328ae0a89523f3876eaa13a7d2fe95d4f8d11c22f4451083d36e384707",
    "cables/green-7.png": "972c21f540bb506c27bbcf4363976dc77d3795c1f17cb3a558db8b379f7a487f",
    "cables/green-8.png": "f30a70e4a457efee2404735ad8fb0b04859305dbe416ac71eb4789a8cac46ca6",
    "cables/grey-0.png": "8a523353d3758b6819677f006c312511a452fcdca40badafa6cdd1c899770262",
    "cables/grey-1.png": "12888a4ff8e3b0293b4367eea3ae6120b94f719c287b8e160c68991d102d084f",
    "cables/grey-2.png": "a44c3f386a5b096de8c6c840ce8e9c293444b43f56a4a06acb0836a823cd0f3a",
    "cables/grey-3.png": "d459f44fddf6aea2c5aefc72677dc120cc30caa0bc8335a0578dc1cc6e107a5d",
    "cables/grey-4.png": "cb14286e88852e90d3568f0e5169f1a0e1bb39e81d7ffa62ba5e40fb6e361e39",
    "cables/grey-5.png": "26f22ea203dd35f2f242a34cb26640668fa206cbbc2c9736f17b0fdbaa5ee95c",
    "cables/grey-6.png": "0742c4e1b7825e24de1d51b33992c0ed73497670add6e9a0c1029e39ac57105d",
    "cables/grey-7.png": "99cb72cecd43fe1f9625fa1491f5684cc753253ea5a197eb0903f2b516b739df",
    "cables/grey-8.png": "bb3bb4d6393a02b8ee48cbdc7d70892d8581084c7fa1d69d930ac1917658119d",
    "cables/magenta-0.png": "bd5ddbd4c54b9ca53a24d6403cacd06d4272e4ea1db831bdf6710db0aa8c2f10",
    "cables/magenta-1.png": "ffb2d77d1eb12d3b0d88780196a4a2576ca389d93b3bf55834a1eaa0549444ad",
    "cables/magenta-2.png": "e19846fb7543345579de569344fcfa124fbf0ea5ce29b4710f17232f4414657b",
    "cables/magenta-3.png": "f9a209612655858e8793c0de0c92185fc8a384c7a2d14cc527585abf2cf53c8f",
    "cables/magenta-4.png": "2ecd5ef95c108c9aba09a76c6141964afc030eaf82922de0f0c02460619e3bba",
    "cables/magenta-5.png": "8d00a319e06c199e22ca5f64d50a41a36b506a4c8497044e441ba1b9ef3a956b",
    "cables/magenta-6.png": "03737715a26d6bfe13f2b219733b2d9728bd758dff7c2d35a4bb8e4e8d8fa300",
    "cables/magenta-7.png": "426e43344252ac86f05cd23b8feeda7aa574adbf6abc2785271cd98b4f087bc9",
    "cables/magenta-8.png": "1a813c68e057ea83009b0750993ff2d517675335d677a9330d51e151adfd5489",
    "cables/orange-0.png": "150037e70e79994f8837777b56291150ea63f7d3154bdf056944f1418d9af72e",
    "cables/orange-1.png": "7ca39704ba7c7f10ff20864c725173df5cae9b61d47700129b656e734e607636",
    "cables/orange-2.png": "9a15498bca0fb98d5724b79fe92349d3e3e883b66f69a14412c6546e19c4221c",
    "cables/orange-3.png": "70f41f879a1f972e86e815b9c6dabde4bb0a89401e98b9da2c7950240d6774f2",
    "cables/orange-4.png": "fd61f121e9794f80cb4e8e8df0b1cb92524babfd629818264349bfae1157373f",
    "cables/orange-5.png": "37891d2690603362b167766a31d297b267ac010cf54e07c9b796f07b53d843da",
    "cables/orange-6.png": "d6076086101899f627cd4fc6e086fdf10953145cf3bf3c0e4dd52c4814d7563f",
    "cables/orange-7.png": "3bd4d77e10dba9780159b2c9267c63ecbf6cb1ab7b055096bd908038ebdf0987",
    "cables/orange-8.png": "be7a9ced5d241d0906b5db307dfd9df63309d82b8aacb3997e5949f2ba3a4062",
    "cables/red-0.png": "27e3b0a1f0a7ab113c3f6c76793da6c31153ffd0f9bc72b368e722d95c8a07f7",
    "cables/red-1.png": "63177a27c4aadea10d3798915bc8f20ce1c62e6c896b89300095566a4d2fe728",
    "cables/red-2.png": "803b449bd4142bd75fe4485ac0294df3ce93d05becadcb4f457dec93578a884a",
    "cables/red-3.png": "d7b2ba5de3534e742fa69091ba0f53b3eebacec40ec01431ce4cbc8ed5b90faa",
    "cables/red-4.png": "14852f43b35cd337731729162adafb3209cca336a7115950533811f0c41fc34a",
    "cables/red-5.png": "254ffe636d132117e32975f5beed2eb6fdbbe647eadb736ee771cae08caedd13",
    "cables/red-6.png": "7c6c14043366422b4e23db5a723f67ae7ce3a3484d66aaa62fd71e20d8c37b08",
    "cables/red-7.png": "49a2260a6f7e210677330bf47dda6dfdaf2fdeb6c6cd4f65a176a3a93ac384e1",
    "cables/red-8.png": "d6b10d1609efeda9f7e279bd19218c4481e53ae18f244f10c3b6fdc5618d5510",
    "cables/white-0.png": "15201bbaa31136b550346d26c2ea1456890f64e296cfce6fcd8e6de68913098a",
    "cables/white-1.png": "5dbc891a97eaf2487489264a3244453a51c723ca1057b7e29afccb712f039549",
    "cables/white-2.png": "acaecf58c1e04da3ead7541e20db935995c9788126bd0f3622819af92ee02bdc",
    "cables/white-3.png": "00537a3062e8946e1440b76898dbfadc12cced2602750732b363de5afdbfe55a",
    "cables/white-4.png": "318375df3ae34f1e06e0579127fa775d78e479c09abf5d6b3c07b62d99a87a6c",
    "cables/white-5.png": "970be7ceced4bcc0efb50cb06eab0e6dc2a2560486721ced86ff245108f6d3f3",
    "cables/white-6.png": "adcb544675aeb9344a68d61f7bd561739300badc92601f37346ffb1d16629b64",
    "cables/white-7.png": "fab996f1a20581b068143cb7b05b117ad3593be4af0b65cdf1df8550352eeec8",
    "cables/white-8.png": "82dbf95cec8b0191f32957948f170932e0c854c651639da224b521ca39d1ac7a",
    "cables/yellow-0.png": "084809a75f18b1bfddbaf2ed01c70a6f65bcac2a5fa2fa50644901dbbf6f98c8",
    "cables/yellow-1.png": "571c7db060abb199bfd894bad0315368af167c0dd8e68e88ddd5a1dff5054ef5",
    "cables/yellow-2.png": "05bf1917f21c8a0f28b00f47a4ff7101df4fba73f98012153a51209abe5a7773",
    "cables/yellow-3.png": "0be6244d0595b965ccef6ad91576c2883969f90b7408b85b85cbd3fae33be38e",
    "cables/yellow-4.png": "cfa88091fc7214976affeef0a31eddb1d12adb0d015a3c45d1dcdfa102a37424",
    "cables/yellow-5.png": "634e9df1724e3284ccc294ee8e32eafcf605eaed7645e22bb29ad2a37036029f",
    "cables/yellow-6.png": "172724ff0fecd3fafe1c851f4884eebcd1b8d6709b6f75f35444737a40d6f9a5",
    "cables/yellow-7.png": "e00b75949bc08ddbf96bca08017069b8a3a32d1f159a587480a621eb9e7ff2a2",
    "cables/yellow-8.png": "3aad2b72146405d5877de103b43565012a55b65bafdb17f65d3482ef8ffd90f3"
}

FILES = [
    (ICON_PREFIX + name, "app/src/main/assets/forge/icons/" + name, digest)
    for name, digest in ICON_HASHES.items()
] + [
    ("droidforge/images/droidforge-icon.png", "app/src/main/assets/forge/droidforge-icon.png",
     "ce7ded80a2a640cddfa9b7238390ad2a862ca3497149e956a87f3bda14d094a5"),
    ("LICENSE", "app/src/main/assets/forge/LICENSE",
     "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"),
    ("CREDITS", "app/src/main/assets/forge/CREDITS",
     "c715c687a98c0ab41e8af3f48e5d48e1be0895856149c3ceb6c902bae18d3161"),
] + [
    ("droidforge/images/" + name, "app/src/main/assets/forge/" + name, digest)
    for name, digest in CABLE_HASHES.items()
]
README_HASH = "23ffe41e277147a1113d8b392b6a503a293e34467a0758cbe319d5d4f67c86d8"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git_file(upstream: Path, source: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(upstream), "show", COMMIT + ":" + source],
        check=True, capture_output=True, timeout=30,
    )
    return result.stdout


def entry(source: str, destination: str, data: bytes, expected: str) -> dict:
    if digest(data) != expected:
        raise ValueError("Pinned source SHA256 mismatch: " + source)
    result = {
        "path": destination,
        "source_path": source,
        "source_url": REPOSITORY + "/blob/" + COMMIT + "/" + source,
        "bytes": len(data),
        "sha256": expected,
    }
    if source.endswith(".png"):
        if len(data) < 24 or not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("Invalid pinned PNG: " + source)
        result["width"], result["height"] = struct.unpack(">II", data[16:24])
    return result


def make_manifest(entries: list[dict]) -> dict:
    return {
        "repository": REPOSITORY,
        "commit": COMMIT,
        "theme": "dark toolbar and original cable artwork",
        "transformation": "none; files copied byte for byte from pinned Git objects",
        "license": {
            "name": "GNU General Public License version 3",
            "declaration_source": REPOSITORY + "/blob/" + COMMIT + "/README",
            "declaration_sha256": README_HASH,
            "bundled_license": "app/src/main/assets/forge/LICENSE",
            "bundled_credits": "app/src/main/assets/forge/CREDITS",
            "application_icon_credit": "Harrison Harrison",
            "other_work_credit": "Mathias Kettner aka Der Mann mit der Maschine",
        },
        "files": entries,
    }


def import_icons(upstream: Path) -> None:
    readme = git_file(upstream, "README")
    if digest(readme) != README_HASH or b"available under the terms of GPL v3" not in readme:
        raise ValueError("Pinned upstream licensing declaration differs")
    # Validate all inputs before writing any output.
    payloads = [(source, destination, git_file(upstream, source), expected)
                for source, destination, expected in FILES]
    entries = [entry(*payload) for payload in payloads]
    for _, destination, data, _ in payloads:
        output = ROOT / destination
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(make_manifest(entries), indent=2) + "\n", encoding="utf-8")


def verify() -> None:
    # Verify without requiring Git, an upstream checkout, or network access.
    entries = [entry(source, destination, (ROOT / destination).read_bytes(), expected)
               for source, destination, expected in FILES]
    expected_manifest = make_manifest(entries)
    actual_manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if actual_manifest != expected_manifest:
        raise ValueError("Forge icon provenance manifest differs from pinned inputs")
    print(json.dumps({
        "verified": len(entries), "pngs": sum(item["source_path"].endswith(".png") for item in entries),
        "cable_pngs": len(CABLE_HASHES),
        "bytes": sum(item["bytes"] for item in entries), "commit": COMMIT,
    }))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, default=ROOT / "upstream/droidforge")
    parser.add_argument("--verify", action="store_true", help="verify bundled files offline")
    args = parser.parse_args()
    if not args.verify:
        import_icons(args.upstream.resolve())
    verify()


if __name__ == "__main__":
    main()
