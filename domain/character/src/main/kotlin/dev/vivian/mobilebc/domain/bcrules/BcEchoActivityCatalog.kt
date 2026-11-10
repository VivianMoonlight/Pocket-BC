package dev.vivian.mobilebc.domain.bcrules

import dev.vivian.mobilebc.core.model.BcEchoDrawRelation
import dev.vivian.mobilebc.core.model.BcEchoLeashRole

/**
 * Versioned Echo action snapshot.
 *
 * The snapshot contains static actions plus a small set of Echo run callbacks whose state
 * requirements and side effects have explicit typed models. Unsupported dynamic actions stay
 * hidden instead of being sent with guessed permissions.
 */
object BcEchoActivityCatalog {
    const val SourceRevision: String = "SugarChain-Studio/echo-activity-ext@main-2026-08-05"

    private val staticRules: List<BcActivityRule> = listOf(
        echoRule("拨弄发丝", "拨弄发丝", selfTargetGroups = setOf("ItemHood"), prerequisites = setOf("UseArms", "UseHands")),
        echoRule("叉腰", "叉腰", selfTargetGroups = setOf("ItemTorso"), prerequisites = setOf("UseArms", "UseHands")),
        echoRule("翅膀环绕", "翅膀环绕", targetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasWings")),
        echoRule("翅膀轻拍", "翅膀轻拍", targetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasWings")),
        echoRule("翅膀扇风", "翅膀扇风", targetGroups = setOf("ItemHead"), prerequisites = setOf("Luzi_HasWings")),
        echoRule("宠物服蹭大腿", "宠物服蹭大腿", targetGroups = setOf("ItemLegs"), prerequisites = setOf("Luzi_HasPetSuit")),
        echoRule("宠物服蹭小腿", "宠物服蹭小腿", targetGroups = setOf("ItemFeet"), prerequisites = setOf("Luzi_HasPetSuit", "MoveHead")),
        echoRule("宠物服爬到脚边", "爬向脚边", targetGroups = setOf("ItemBoots"), prerequisites = setOf("Luzi_HasPetSuit")),
        echoRule("触手缠绕手腕", "触手缠绕手腕", targetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_HasTentacles")),
        echoRule("触手戳", "触手戳鼻子", targetGroups = setOf("ItemNose", "ItemMouth", "ItemBreast", "ItemButt"), selfTargetGroups = setOf("ItemNose", "ItemMouth", "ItemBreast", "ItemButt"), prerequisites = setOf("Luzi_HasTentacles")),
        echoRule("触手摸头", "触手摸头", targetGroups = setOf("ItemHead"), selfTargetGroups = setOf("ItemHead"), prerequisites = setOf("Luzi_HasTentacles")),
        echoRule("触手轻抚鼻尖", "触手轻抚鼻尖", targetGroups = setOf("ItemNose"), selfTargetGroups = setOf("ItemNose"), prerequisites = setOf("Luzi_HasTentacles")),
        echoRule("触手揉鼻子", "触手揉鼻子", targetGroups = setOf("ItemNose"), selfTargetGroups = setOf("ItemNose"), prerequisites = setOf("Luzi_HasTentacles")),
        echoRule("触手揉脸", "触手揉脸", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_HasTentacles")),
        echoRule("触手掀裙子", "触手掀裙子", targetGroups = setOf("ItemButt"), selfTargetGroups = setOf("ItemButt"), prerequisites = setOf("Luzi_HasTentacles")),
        echoRule("打哈欠", "打哈欠", selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("UseMouth")),
        echoRule("弹阴蒂", "弹阴蒂", targetGroups = setOf("ItemVulvaPiercings"), prerequisites = setOf("UseHands", "UseArms", "Luzi_ActedZoneNaked")),
        echoRule("踮起脚尖", "踮起脚尖", selfTargetGroups = setOf("ItemBoots")),
        echoRule("嘟囔", "嘟囔", selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("UseMouth")),
        echoRule("跺脚", "跺跺脚", selfTargetGroups = setOf("ItemBoots")),
        echoRule("耳朵呵气", "耳朵呵气", targetGroups = setOf("ItemEars"), prerequisites = setOf("UseMouth")),
        echoRule("盖住耳朵", "盖住耳朵", targetGroups = setOf("ItemEars"), selfTargetGroups = setOf("ItemEars"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("环视周围", "环视周围", selfTargetGroups = setOf("ItemNeck", "ItemHead")),
        echoRule("挥手致意", "挥手致意", targetGroups = setOf("ItemHands"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("剑架在脖子上", "剑架在脖子上", targetGroups = setOf("ItemNeck", "ItemNeckRestraints", "ItemNeckAccessories"), selfTargetGroups = setOf("ItemNeck", "ItemNeckRestraints", "ItemNeckAccessories"), prerequisites = setOf("UseHands", "UseArms", "Luzi_HasSword")),
        echoRule("剑拍脸", "剑拍脸", targetGroups = setOf("ItemMouth"), prerequisites = setOf("UseHands", "UseArms", "Luzi_HasSword")),
        echoRule("脚按摩", "脚按摩", targetGroups = setOf("ItemPelvis", "ItemLegs", "ItemBreast"), prerequisites = setOf("UseFeet", "Luzi_TargetKneelOrAllFours")),
        echoRule("脚戳刺激", "脚戳脸", targetGroups = setOf("ItemNipples", "ItemVulva", "ItemVulvaPiercings", "ItemButt"), prerequisites = setOf("UseFeet", "Luzi_IsStanding", "TargetZoneNaked", "Luzi_TargetKneelOrAllFours")),
        echoRule("脚戳低处", "脚戳脸", targetGroups = setOf("ItemFeet", "ItemBoots"), prerequisites = setOf("UseFeet", "Luzi_IsStanding")),
        echoRule("脚托起下巴", "足尖挑下巴", targetGroups = setOf("ItemNeck", "ItemMouth"), prerequisites = setOf("UseFeet", "Luzi_IsStanding", "Luzi_TargetKneelOrAllFours")),
        echoRule("啃咬笼锁", "啃咬笼锁", selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_HasKennel", "UseMouth")),
        echoRule("口塞亲吻嘴唇", "口塞亲吻嘴唇", targetGroups = setOf("ItemMouth"), prerequisites = setOf("IsGagged")),
        echoRule("流出液体", "流出液体", selfTargetGroups = setOf("ItemVulva"), prerequisites = setOf("Luzi_TargetHasItemVulva")),
        echoRule("流口水", "流口水", selfTargetGroups = setOf("ItemMouth")),
        echoRule("流眼泪", "流眼泪", selfTargetGroups = setOf("ItemHead")),
        echoRule("搂腰", "搂腰", targetGroups = setOf("ItemTorso"), prerequisites = setOf("UseArms", "UseHands")),
        echoRule("猫爪按手", "猫爪按手", targetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪戳鼻子", "猫爪轻点鼻尖", targetGroups = setOf("ItemNose"), selfTargetGroups = setOf("ItemNose"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪戳脸", "猫爪轻戳脸颊", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪护裆", "猫爪防护", targetGroups = setOf("ItemVulva"), selfTargetGroups = setOf("ItemVulva"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪画圈", "猫爪画圈圈", targetGroups = setOf("ItemPelvis"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪摸脸", "猫爪摸脸", targetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪挠手", "猫爪轻挠手心", targetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪挠手臂", "猫爪轻挠手臂", targetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪暖腹", "猫爪暖腹", targetGroups = setOf("ItemPelvis"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪拍肚", "猫爪拍肚", targetGroups = setOf("ItemPelvis"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪拍肩", "猫爪拍肩", targetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪拍手", "猫爪击掌", targetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪拍头", "猫爪拍头", targetGroups = setOf("ItemHead"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪碰鼻", "猫爪碰鼻", targetGroups = setOf("ItemNose"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪轻按", "猫爪轻按", targetGroups = setOf("ItemPelvis"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪揉鼻子", "猫爪轻揉鼻头", targetGroups = setOf("ItemNose"), selfTargetGroups = setOf("ItemNose"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪揉肚", "猫爪揉肚", targetGroups = setOf("ItemPelvis"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪揉脸", "猫爪轻揉脸蛋", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪梳毛", "猫爪顺毛", targetGroups = setOf("ItemHead"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪舔手", "舔舐猫爪", selfTargetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms", "UseTongue")),
        echoRule("猫爪握手", "猫爪握手手", targetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("猫爪遮眼", "爪爪遮眼睛", targetGroups = setOf("ItemHead"), prerequisites = setOf("Luzi_HasPawMittens", "UseArms")),
        echoRule("摩擦笼子", "摩擦笼子", selfTargetGroups = setOf("ItemVulva"), prerequisites = setOf("Luzi_HasKennel", "Luzi_Female", "Luzi_ActedZoneNaked")),
        echoRule("挠头", "挠头", selfTargetGroups = setOf("ItemHead"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("捏脸", "捏脸", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("捏猫爪", "揉捏猫爪", targetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_TargetHasPawMittens", "UseArms")),
        echoRule("扭动手腕", "扭动手腕", selfTargetGroups = setOf("ItemHands"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("撇眼", "撇眼", targetGroups = setOf("ItemHead")),
        echoRule("轻扯衣角", "轻扯衣角", targetGroups = setOf("ItemPelvis"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("轻弹额头", "轻弹额头", targetGroups = setOf("ItemHead"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("轻拍脑袋", "轻拍脑袋", targetGroups = setOf("ItemHead"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("轻声喘息", "轻声喘息", selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("UseTongue")),
        echoRule("轻抬下巴", "手指抬下巴", targetGroups = setOf("ItemNeck", "ItemMouth"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("轻推", "轻推身体", targetGroups = setOf("ItemTorso"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("蜷缩角落", "蜷缩角落", selfTargetGroups = setOf("ItemTorso"), prerequisites = setOf("Luzi_HasKennel")),
        echoRule("揉搓乳头", "揉搓乳头", targetGroups = setOf("ItemNipples"), selfTargetGroups = setOf("ItemNipples"), prerequisites = setOf("UseHands", "UseArms", "Luzi_ActedZoneNaked")),
        echoRule("揉脸", "揉脸", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("乳头呵气", "乳头呵气", targetGroups = setOf("ItemNipples"), prerequisites = setOf("UseMouth", "Luzi_ActedZoneNaked")),
        echoRule("扇动翅膀", "振翅", selfTargetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasWings")),
        echoRule("上下打量", "上下打量", targetGroups = setOf("ItemHead")),
        echoRule("伸出脚", "伸出腿脚", selfTargetGroups = setOf("ItemBoots"), prerequisites = setOf("UseFeet")),
        echoRule("伸出手", "伸出手", selfTargetGroups = setOf("ItemHands"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("身体颤抖", "身体颤抖", selfTargetGroups = setOf("ItemTorso")),
        echoRule("身体抽搐", "身体抽搐", selfTargetGroups = setOf("ItemTorso")),
        echoRule("失禁", "失禁", selfTargetGroups = setOf("ItemVulva"), prerequisites = setOf("Luzi_TargetHasItemVulva")),
        echoRule("收拢翅膀", "收拢翅膀", selfTargetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasWings")),
        echoRule("手臂搭在肩膀上", "搭肩", targetGroups = setOf("ItemNeck"), prerequisites = setOf("UseArms")),
        echoRule("竖起尾巴", "竖起尾巴", selfTargetGroups = setOf("ItemButt"), prerequisites = setOf("Luzi_HasCatTail")),
        echoRule("双腿颤抖", "双腿发颤", selfTargetGroups = setOf("ItemLegs"), prerequisites = setOf("Luzi_TargetHasItemVulva")),
        echoRule("抬起屁股", "抬起屁股", selfTargetGroups = setOf("ItemButt")),
        echoRule("舔触手", "舔触手", selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_TargetHasTentacles")),
        echoRule("舔脚", "舔脚", targetGroups = setOf("ItemBoots"), selfTargetGroups = setOf("ItemBoots"), prerequisites = setOf("UseTongue")),
        echoRule("舔脸", "舔脸", targetGroups = setOf("ItemMouth"), prerequisites = setOf("UseTongue")),
        echoRule("舔手", "舔手", targetGroups = setOf("ItemHands"), selfTargetGroups = setOf("ItemHands"), prerequisites = setOf("UseMouth", "Luzi_ActedZoneNaked")),
        echoRule("舔手指", "舔手指", targetGroups = setOf("ItemHands"), selfTargetGroups = setOf("ItemHands"), prerequisites = setOf("UseMouth", "Luzi_ActedZoneNaked")),
        echoRule("舔尾巴", "舔尾巴", targetGroups = setOf("ItemButt"), selfTargetGroups = setOf("ItemButt"), prerequisites = setOf("Luzi_TargetHasCatTail", "UseTongue")),
        echoRule("推挤笼门", "推挤笼门", selfTargetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasKennel")),
        echoRule("吞咽口水", "吞咽口水", selfTargetGroups = setOf("ItemNeck")),
        echoRule("托起乳房", "托起乳房", targetGroups = setOf("ItemBreast"), selfTargetGroups = setOf("ItemBreast")),
        echoRule("歪头", "歪头", selfTargetGroups = setOf("ItemNeck", "ItemHead"), prerequisites = setOf("MoveHead")),
        echoRule("尾巴缠绕", "尾巴缠绕", targetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasCatTail")),
        echoRule("尾巴戳脸", "尾巴戳脸", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_HasCatTail")),
        echoRule("尾巴戳胸", "尾巴戳胸", targetGroups = setOf("ItemBreast"), selfTargetGroups = setOf("ItemBreast"), prerequisites = setOf("Luzi_HasCatTail", "Luzi_TargetHasBreast")),
        echoRule("尾巴拍打地面", "尾巴拍打地面", selfTargetGroups = setOf("ItemButt"), prerequisites = setOf("Luzi_HasTail")),
        echoRule("尾巴炸毛", "尾巴炸毛", selfTargetGroups = setOf("ItemButt"), prerequisites = setOf("Luzi_HasCatTail")),
        echoRule("尾鳍骚挠鼻子", "尾鳍骚挠鼻子", targetGroups = setOf("ItemNose"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("捂住头", "捂住头", targetGroups = setOf("ItemHead"), selfTargetGroups = setOf("ItemHead"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("捂住下体", "捂住下体", targetGroups = setOf("ItemVulva"), selfTargetGroups = setOf("ItemVulva"), prerequisites = setOf("UseHands", "UseArms", "Luzi_ActedZoneNaked")),
        echoRule("捂住胸", "捂住胸", selfTargetGroups = setOf("ItemBreast"), prerequisites = setOf("UseHands", "UseArms", "Luzi_HasBreast", "Luzi_ActedZoneNaked")),
        echoRule("下巴搭在肩膀上", "倚靠肩头", targetGroups = setOf("ItemNeck")),
        echoRule("掀开裙子", "掀开裙子", targetGroups = setOf("ItemButt"), selfTargetGroups = setOf("ItemButt"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("衔尾", "衔尾", targetGroups = setOf("ItemButt"), selfTargetGroups = setOf("ItemButt"), prerequisites = setOf("Luzi_TargetHasCatTail", "UseTongue")),
        echoRule("嗅手", "嗅手", targetGroups = setOf("ItemHands"), selfTargetGroups = setOf("ItemHands")),
        echoRule("眼睛呆滞", "眼睛呆滞", selfTargetGroups = setOf("ItemHead")),
        echoRule("眼睛湿润", "眼睛湿润", selfTargetGroups = setOf("ItemHead")),
        echoRule("摇晃脚踝", "晃动脚踝", selfTargetGroups = setOf("ItemBoots")),
        echoRule("摇晃笼子", "摇晃笼子", selfTargetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasKennel", "UseArms")),
        echoRule("摇晃手臂", "摇晃手臂", targetGroups = setOf("ItemArms"), selfTargetGroups = setOf("ItemArms"), prerequisites = setOf("UseHands")),
        echoRule("摇晃双腿", "摇晃双腿", selfTargetGroups = setOf("ItemLegs")),
        echoRule("咬笼子", "啃咬笼栏", selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_HasKennel", "UseMouth")),
        echoRule("阴部轻蹭手", "阴部轻蹭", targetGroups = setOf("ItemHands"), prerequisites = setOf("HasVagina", "Luzi_PrivateExposed")),
        echoRule("阴茎摩擦", "阴茎摩擦", targetGroups = setOf("ItemHands", "ItemArms", "ItemFeet", "ItemLegs", "ItemButt", "ItemBreast"), prerequisites = setOf("Luzi_CanWalk", "Luzi_HasPenis", "Luzi_PrivateExposed")),
        echoRule("鱼尾缠绕", "鱼尾缠绕", targetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾戳脸", "鱼尾戳脸", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾戳乳头", "鱼尾戳乳头", targetGroups = setOf("ItemNipples"), selfTargetGroups = setOf("ItemNipples"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾担膝盖", "鱼尾担膝盖", targetGroups = setOf("ItemLegs"), prerequisites = setOf("Luzi_Has鱼鱼尾", "TargetKneeling")),
        echoRule("鱼尾抚脸", "鱼尾抚脸", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾抚弄大腿", "鱼尾抚弄大腿", targetGroups = setOf("ItemLegs"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾挠肋", "鱼尾挠肋", targetGroups = setOf("ItemTorso"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾碰手", "鱼尾碰手", targetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾揉脸", "鱼尾揉脸", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾揉乳房", "鱼尾揉乳房", targetGroups = setOf("ItemBreast"), selfTargetGroups = setOf("ItemBreast"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾扇风", "鱼尾扇风", targetGroups = setOf("ItemMouth"), selfTargetGroups = setOf("ItemMouth"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾松绑", "鱼尾松绑", targetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾松手", "鱼尾松手", targetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("鱼尾抓手", "鱼尾抓手", targetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_Has鱼鱼尾")),
        echoRule("炸毛", "炸毛", selfTargetGroups = setOf("ItemButt"), prerequisites = setOf("Luzi_HasCatTail")),
        echoRule("遮住眼睛", "遮住眼睛", targetGroups = setOf("ItemHead"), selfTargetGroups = setOf("ItemHead"), prerequisites = setOf("UseHands", "UseArms")),
        echoRule("抓挠笼子", "抓挠笼子", selfTargetGroups = setOf("ItemHands"), prerequisites = setOf("Luzi_HasKennel", "UseArms", "UseHands")),
        echoRule("撞笼子", "撞击笼子", selfTargetGroups = setOf("ItemArms"), prerequisites = setOf("Luzi_HasKennel")),
    )

    val rules: List<BcActivityRule> = staticRules + listOf(
        effectRule("跪下", "ItemLegs", setOf("Luzi_IsStanding", "CanKneel"), BcActivityEffect.Pose(listOf("Kneel"))),
        effectRule("站起来", "ItemLegs", setOf("Luzi_IsKneeling", "CanBaseLower"), BcActivityEffect.Pose(emptyList())),
        effectRule("跪着张开腿", "ItemLegs", setOf("Luzi_IsKneeling", "CanKneelingSpread"), BcActivityEffect.Pose(listOf("KneelingSpread"))),
        effectRule("跪着并拢腿", "ItemLegs", setOf("Luzi_IsKneeling", "CanKneel"), BcActivityEffect.Pose(listOf("Kneel"))),
        effectRule("四肢着地", "ItemBoots", setOf("CanAllFours"), BcActivityEffect.Pose(listOf("AllFours"))),
        effectRule("起身跪下", "ItemBoots", setOf("Luzi_IsAllFours", "CanKneel"), BcActivityEffect.Pose(listOf("Kneel"))),
        effectRule("宠物服趴下", "ItemLegs", setOf("Luzi_HasPetSuit", "Luzi_IsKneeling", "CanAllFours"), BcActivityEffect.Pose(listOf("AllFours"))),
        effectRule("宠物服跪立", "ItemLegs", setOf("Luzi_HasPetSuit", "Luzi_IsAllFours", "CanKneel"), BcActivityEffect.Pose(listOf("Kneel"))),
        effectRule("张开嘴", "ItemMouth", setOf("UseMouth"), BcActivityEffect.Expression("Mouth", "Open")),
        effectRule("闭上嘴", "ItemMouth", setOf("UseMouth"), BcActivityEffect.Expression("Mouth", null)),
        effectRule("闭上眼睛", "ItemHead", emptySet(), BcActivityEffect.Expression("Eyes", "Closed")),
        pairedItemRule(
            name = "钻进怀里",
            actorItemName = "贴贴",
            actorRelation = BcEchoDrawRelation.PreviousCharacter,
            actorLeash = BcEchoLeashRole.Lead,
            targetGroups = setOf("ItemTorso", "ItemTorso2", "ItemArms"),
            prerequisites = setOf("Luzi_CanWalk"),
            stateRequirements = listOf(
                BcActivityStateRequirement.GroupEmpty(BcActivityParticipant.Actor, "ItemMisc"),
                BcActivityStateRequirement.GroupEmpty(BcActivityParticipant.Target, "ItemMisc"),
            ),
        ),
        pairedItemRule(
            name = "抱入怀中",
            actorItemName = "贴贴",
            actorRelation = BcEchoDrawRelation.NextCharacter,
            actorLeash = BcEchoLeashRole.Follow,
            targetGroups = setOf("ItemTorso", "ItemTorso2", "ItemArms"),
            prerequisites = setOf("Luzi_CanWalk"),
            stateRequirements = listOf(
                BcActivityStateRequirement.GroupEmpty(BcActivityParticipant.Actor, "ItemMisc"),
                BcActivityStateRequirement.GroupEmpty(BcActivityParticipant.Target, "ItemMisc"),
            ),
        ),
        pairedItemRule(
            name = "扛起",
            displayName = "背起",
            actorItemName = "扛起来的麻袋",
            actorRelation = BcEchoDrawRelation.PreviousCharacter,
            actorLeash = BcEchoLeashRole.Lead,
            targetGroups = setOf("ItemTorso"),
            stateRequirements = listOf(
                BcActivityStateRequirement.GroupEmptyOrContainsAny(
                    participant = BcActivityParticipant.Actor,
                    group = "ItemMisc",
                    names = setOf("扛起来的麻袋"),
                ),
                BcActivityStateRequirement.GroupContainsAny(
                    participant = BcActivityParticipant.Target,
                    group = "ItemDevices",
                    names = setOf("BurlapSack"),
                ),
            ),
            targetItemGroup = "ItemDevices",
            targetItemName = "BurlapSack",
            targetRequiresAllowItem = false,
        ),
        pairedItemRule(
            name = "手推车",
            displayName = "抓住推车",
            actorItemName = "抓住推车",
            actorRelation = BcEchoDrawRelation.NextCharacter,
            actorLeash = BcEchoLeashRole.Lead,
            targetGroups = setOf("ItemTorso"),
            prerequisites = setOf("UseHands", "Luzi_TargetLeashedOrCanBeLeashed"),
            stateRequirements = listOf(
                BcActivityStateRequirement.GroupEmptyOrContainsAny(
                    participant = BcActivityParticipant.Actor,
                    group = "ItemMisc",
                    names = setOf("抓住推车"),
                ),
                BcActivityStateRequirement.GroupContainsAny(
                    participant = BcActivityParticipant.Target,
                    group = "ItemDevices",
                    names = setOf("Trolley"),
                ),
            ),
            targetItemGroup = "ItemDevices",
            targetItemName = "Trolley",
        ),
    )

    fun localizedRules(language: String): List<BcActivityRule> {
        if (!language.equals("EN", ignoreCase = true)) return rules
        return rules.map { rule ->
            val localized = englishOverrides[rule.name] ?: return@map rule
            rule.copy(
                displayName = localized.displayName,
                chatText = localized.otherChatText ?: rule.chatText,
                selfChatText = localized.selfChatText ?: rule.selfChatText,
            )
        }
    }

    fun localizedChatTemplate(
        activityName: String,
        language: String,
        targetGroup: String,
        isSelfTarget: Boolean,
    ): String? {
        if (activityName !in englishOverrides) return null
        return localizedRules(language)
            .firstOrNull { it.name == activityName }
            ?.chatTextFor(targetGroup, isSelfTarget)
    }

    fun mergeInto(catalog: BcRuleCatalog, language: String = "CN"): BcRuleCatalog {
        val mergedGroups = catalog.groups.toMutableMap()
        val mergedActivities = catalog.activities.toMutableMap()
        val activeRules = localizedRules(language)
        val collisions = activeRules.map(BcActivityRule::name).filter(catalog.activities::containsKey).toSet()
        activeRules.forEach { rule ->
            (rule.targetGroups + rule.selfTargetGroups).forEach { group ->
                mergedGroups.putIfAbsent(
                    group,
                    BcGroupRule(name = group, category = "Item", isItem = group.startsWith("Item")),
                )
            }
            mergedActivities.putIfAbsent(rule.name, rule)
        }
        return catalog.copy(
            groups = mergedGroups,
            activities = mergedActivities,
            health = catalog.health.copy(echoNativeCollisions = collisions),
        )
    }

    private val englishOverrides: Map<String, EchoEnglishText> = mapOf(
        "钻进怀里" to EchoEnglishText(
            displayName = "Get In Arms",
            otherChatText = "SourceCharacter snuggles into DestinationCharacter arms.",
        ),
        "抱入怀中" to EchoEnglishText(
            displayName = "Hold In Arms",
            otherChatText = "SourceCharacter holds TargetCharacter in arms.",
        ),
        "扛起" to EchoEnglishText(
            displayName = "Carry on the Back",
            otherChatText = "SourceCharacter carries TargetCharacter on the back.",
        ),
        "手推车" to EchoEnglishText(
            displayName = "Grab the Trolley",
            otherChatText = "SourceCharacter grabs TargetCharacter's trolley.",
        ),
    )

    private data class EchoEnglishText(
        val displayName: String,
        val otherChatText: String? = null,
        val selfChatText: String? = null,
    )
}

private fun effectRule(
    name: String,
    selfTargetGroup: String,
    prerequisites: Set<String>,
    effect: BcActivityEffect,
): BcActivityRule = echoRule(
    name = name,
    displayName = name,
    selfTargetGroups = setOf(selfTargetGroup),
    prerequisites = prerequisites,
).copy(effects = listOf(effect))

private fun pairedItemRule(
    name: String,
    displayName: String = name,
    actorItemGroup: String = "ItemMisc",
    actorItemName: String,
    actorRelation: BcEchoDrawRelation,
    actorLeash: BcEchoLeashRole,
    targetGroups: Set<String>,
    prerequisites: Set<String> = emptySet(),
    stateRequirements: List<BcActivityStateRequirement>,
    targetItemGroup: String = actorItemGroup,
    targetItemName: String = actorItemName,
    targetRequiresAllowItem: Boolean = true,
): BcActivityRule = echoRule(
    name = name,
    displayName = displayName,
    targetGroups = targetGroups,
    prerequisites = prerequisites,
).copy(
    stateRequirements = stateRequirements,
    effects = listOf(
        BcActivityEffect.EchoPairedItem(
            group = actorItemGroup,
            name = actorItemName,
            actorRelation = actorRelation,
            actorLeash = actorLeash,
            targetGroup = targetItemGroup,
            targetName = targetItemName,
            targetRequiresAllowItem = targetRequiresAllowItem,
        ),
    ),
)

private fun echoRule(
    name: String,
    displayName: String,
    targetGroups: Set<String> = emptySet(),
    selfTargetGroups: Set<String> = emptySet(),
    prerequisites: Set<String> = emptySet(),
): BcActivityRule {
    val message = BcEchoActivityMessages[name]
    return BcActivityRule(
        name = name,
        displayName = displayName,
        targetGroups = targetGroups,
        selfTargetGroups = selfTargetGroups,
        prerequisites = prerequisites,
        source = BcActivitySource.Echo,
        chatText = message?.other ?: targetGroups.takeIf { it.isNotEmpty() }
            ?.let { "SourceCharacter对TargetCharacter执行了“$displayName”。" },
        chatTextByTargetGroup = message?.otherByGroup.orEmpty(),
        selfChatText = message?.self ?: selfTargetGroups.takeIf { it.isNotEmpty() }
            ?.let { "SourceCharacter执行了“$displayName”。" },
        selfChatTextByTargetGroup = message?.selfByGroup.orEmpty(),
    )
}
