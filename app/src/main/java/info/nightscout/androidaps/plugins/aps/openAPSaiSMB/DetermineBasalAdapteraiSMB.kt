package info.nightscout.androidaps.plugins.aps.openAPSaiSMB

import android.content.Context
import android.os.Environment
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.IobTotal
import info.nightscout.androidaps.data.MealData
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.extensions.convertedToAbsolute
import info.nightscout.androidaps.extensions.getPassedDurationToTimeInMinutes
import info.nightscout.androidaps.extensions.plannedRemainingMinutes
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.plugins.aps.loop.APSResult
import info.nightscout.androidaps.plugins.aps.openAPSSMB.SMBDefaults
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.stats.TddCalculator
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.logging.LTag
import info.nightscout.shared.sharedPreferences.SP
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.*
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt

class DetermineBasalAdapteraiSMB internal constructor(context: Context, injector: HasAndroidInjector) : DetermineBasalAdapterInterface {

    private val injector: HasAndroidInjector

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintChecker
    @Inject lateinit var sp: SP
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var repository: AppRepository
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var tddCalculator: TddCalculator

    private val mContext: Context
    private var iob = 0.0f
    private var cob = 0.0f
    private var lastCarbAgeMin: Int = 0
    private var futureCarbs = 0.0f
    private var bg = 0.0f
    private var targetBg = 100.0f
    private var delta = 0.0f
    private var shortAvgDelta = 0.0f
    private var longAvgDelta = 0.0f
    private var noise = 0.0f
    private var accelerating_up: Int = 0
    private var deccelerating_up: Int = 0
    private var accelerating_down: Int = 0
    private var deccelerating_down: Int = 0
    private var stable: Int = 0
    private var maxIob = 0.0f
    private var maxSMB = 1.0f
    private var tdd7Days = 0.0f
    private var tdd7DaysPerHour = 0.0f
    private var tddDaily = 0.0f
    private var tddPerHour = 0.0f
    private var tdd24Hrs = 0.0f
    private var tdd24HrsPerHour = 0.0f
    private var hourOfDay: Int = 0
    private var hour0_2: Int = 0
    private var hour3_5: Int = 0
    private var hour6_8: Int = 0
    private var hour9_11: Int = 0
    private var hour12_14: Int = 0
    private var hour15_17: Int = 0
    private var hour18_20: Int = 0
    private var hour21_23: Int = 0
    private var weekend: Int = 0
    private var recentSteps5Minutes: Int = 0
    private var recentSteps10Minutes: Int = 0
    private var recentSteps15Minutes: Int = 0
    private var recentSteps30Minutes: Int = 0
    private var recentSteps60Minutes: Int = 0
    private var sleep: Int = 0
    private var sedentary: Int = 0

    private var profile = JSONObject()
    private var glucoseStatus = JSONObject()
    private var iobData: JSONArray? = null
    private var mealData = JSONObject()
    private var currentTemp = JSONObject()
    private var autosensData = JSONObject()
    private val path = File(Environment.getExternalStorageDirectory().toString())
    private val modelFileName = "AAPS/ml/model_1.pt"
    private val modelFile = File(path, modelFileName)

    override var currentTempParam: String? = null
    override var iobDataParam: String? = null
    override var glucoseStatusParam: String? = null
    override var profileParam: String? = null
    override var mealDataParam: String? = null
    override var scriptDebug = ""

    @Suppress("SpellCheckingInspection")
    override operator fun invoke(): APSResult {
        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal <<<")

        val predictedSMB = calculateSMBFromModel()
        var smbToGive = predictedSMB

        smbToGive = applySafetyPrecautions(smbToGive)
        smbToGive = roundToPoint05(smbToGive)

        logDataToCsv(predictedSMB, smbToGive)

        val constraintStr = " Max IOB: $maxIob \n Max SMB: $maxSMB"
        val glucoseStr = " bg: $bg \n targetBg: $targetBg \n delta: $delta \n short avg delta: $shortAvgDelta \n long avg delta: $longAvgDelta \n noise: $noise \n" +
            " accelerating_up: $accelerating_up \n deccelerating_up: $deccelerating_up \n accelerating_down: $accelerating_down \n deccelerating_down: $deccelerating_down \n stable: $stable"
        val iobStr = " IOB: $iob \n tdd 7d: ${roundToPoint05(tdd7Days)} : ${roundToPoint05(tdd7DaysPerHour)} \n " +
            "tdd daily: $tddDaily : ${roundToPoint05(tddPerHour)} \n " +
            "tdd 24h: $tdd24Hrs : ${roundToPoint05(tdd24HrsPerHour)}"
        val profileStr = " Hour of day: $hourOfDay \n Weekend: $weekend \n" +
            " 5 Min Steps: $recentSteps5Minutes \n 10 Min Steps: $recentSteps10Minutes \n 15 Min Steps: $recentSteps15Minutes \n" +
            " 30 Min Steps: $recentSteps30Minutes \n 60 Min Steps: $recentSteps60Minutes \n" +
            " Sleep: $sleep \n Sedentary: $sedentary"
        val mealStr = " COB: $cob\n COB Age Min: $lastCarbAgeMin\n Future COB: $futureCarbs"
        val reason = "The ai model predicted SMB of ${predictedSMB}u and after safety requirements and rounding to .05, requested ${smbToGive}u to the pump"

        val determineBasalResultaiSMB = DetermineBasalResultaiSMB(injector, smbToGive, constraintStr, glucoseStr, iobStr, profileStr, mealStr, reason)

        glucoseStatusParam = glucoseStatus.toString()
        iobDataParam = iobData.toString()
        currentTempParam = currentTemp.toString()
        profileParam = profile.toString()
        mealDataParam = mealData.toString()
        return determineBasalResultaiSMB
    }

    private fun logDataToCsv(predictedSMB: Float, smbToGive: Float) {
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now())

        val headerRow = "dateStr,dateLong," +
            "hourOfDay,hour0_2,hour3_5,hour6_8,hour9_11,hour12_14,hour15_17,hour18_20,hour21_23,weekend," +
            "bg,targetBg,iob,cob,lastCarbAgeMin,futureCarbs,delta,shortAvgDelta,longAvgDelta,noise," +
            "accelerating_up,deccelerating_up,accelerating_down,deccelerating_down,stable," +
            "tdd7Days,tdd7DaysPerHour,tddDaily,tddPerHour,tdd24Hrs,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes," +
            "sleep,sedentary," +
            "predictedSMB,maxIob,maxSMB,smbGiven\n"
        val valuesToRecord = "$dateStr,${dateUtil.now()}," +
            "$hourOfDay,$hour0_2,$hour3_5,$hour6_8,$hour9_11,$hour12_14,$hour15_17,$hour18_20,$hour21_23,$weekend," +
            "$bg,$targetBg,$iob,$cob,$lastCarbAgeMin,$futureCarbs,$delta,$shortAvgDelta,$longAvgDelta,$noise," +
            "$accelerating_up,$deccelerating_up,$accelerating_down,$deccelerating_down,$stable," +
            "$tdd7Days,$tdd7DaysPerHour,$tddDaily,$tddPerHour,$tdd24Hrs,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes," +
            "$sleep,$sedentary," +
            "$predictedSMB,$maxIob,$maxSMB,$smbToGive"

        val file = File(path, "AAPS/aiSMB_records.csv")
        if (!file.exists()) {
            file.createNewFile()
            file.appendText(headerRow)
        }
        file.appendText(valuesToRecord + "\n")
    }

    private fun applySafetyPrecautions(smbToGiveParam: Float): Float {
        var smbToGive = smbToGiveParam
        // don't exceed max IOB
        if (iob + smbToGive > maxIob) {
            smbToGive = maxIob - iob
        }
        // don't exceed max SMB
        if (smbToGive > maxSMB) {
            smbToGive = maxSMB
        }
        // don't give insulin if below target
        val belowTargetAndDropping = bg < targetBg && delta < -1
        val belowTargetAndStableButNoCob = bg < targetBg && delta <= 2 && cob <= 5
        val belowMinThreshold = bg < 70
        if (belowTargetAndDropping || belowMinThreshold || belowTargetAndStableButNoCob) {
            smbToGive = 0.0f
        }

        // don't give insulin if dropping fast
        val droppingFast = bg < 150 && delta < -5
        val droppingFastAtHigh = bg < 200 && delta < -7
        val droppingVeryFast = delta < -10
        if (droppingFast || droppingFastAtHigh || droppingVeryFast) {
            smbToGive = 0.0f
        }
        if (smbToGive < 0.0f) {
            smbToGive = 0.0f
        }
        return smbToGive
    }

    private fun roundToPoint05(number: Float): Float {
        return (number * 20.0).roundToInt() / 20.0f
    }

    private fun calculateSMBFromModel(): Float {
        if (!modelFile.exists()) {
            aapsLogger.error(LTag.APS, "NO Model found at $modelFileName")
            return 0.0f
        }

        //@@bp
        //val interpreter = Interpreter(modelFile)


        val modelInputs = floatArrayOf(
            hourOfDay.toFloat(), hour0_2.toFloat(), hour3_5.toFloat(), hour6_8.toFloat(), hour9_11.toFloat(),
            hour12_14.toFloat(), hour15_17.toFloat(), hour18_20.toFloat(), hour21_23.toFloat(), weekend.toFloat(),
            bg, targetBg, iob, cob, lastCarbAgeMin.toFloat(), futureCarbs, delta, shortAvgDelta, longAvgDelta,
            accelerating_up.toFloat(), deccelerating_up.toFloat(), accelerating_down.toFloat(), deccelerating_down.toFloat(), stable.toFloat(),
            tdd7Days, tdd7DaysPerHour, tddDaily, tddPerHour, tdd24Hrs, tdd24HrsPerHour,
            recentSteps5Minutes.toFloat(), recentSteps10Minutes.toFloat(), recentSteps15Minutes.toFloat(), recentSteps30Minutes.toFloat(), recentSteps60Minutes.toFloat(),
            sleep.toFloat(), sedentary.toFloat()
        )
        val output = arrayOf(floatArrayOf(0.0f))

        //@@bp
        // interpreter.run(modelInputs, output)
        // interpreter.close()

        var smbToGive = output[0][0]
        smbToGive = "%.4f".format(smbToGive.toDouble()).toFloat()
        return smbToGive
    }

    @Suppress("SpellCheckingInspection")
    @Throws(JSONException::class)
    override fun setData(
        profile: Profile,
        maxIob: Double,
        maxBasal: Double,
        minBg: Double,
        maxBg: Double,
        targetBg: Double,
        basalRate: Double,
        iobArray: Array<IobTotal>,
        glucoseStatus: GlucoseStatus,
        mealData: MealData,
        autosensDataRatio: Double,
        tempTargetSet: Boolean,
        microBolusAllowed: Boolean,
        uamAllowed: Boolean,
        advancedFiltering: Boolean,
        isSaveCgmSource: Boolean
    ) {
        val now = System.currentTimeMillis()
        this.hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hour0_2 = if (hourOfDay in 0..2) 1 else 0
        hour3_5 = if (hourOfDay in 3..5) 1 else 0
        hour6_8 = if (hourOfDay in 6..8) 1 else 0
        hour9_11 = if (hourOfDay in 9..11) 1 else 0
        hour12_14 = if (hourOfDay in 12..14) 1 else 0
        hour15_17 = if (hourOfDay in 15..17) 1 else 0
        hour18_20 = if (hourOfDay in 18..18) 1 else 0
        hour21_23 = if (hourOfDay in 21..23) 1 else 0
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        this.weekend = if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) 1 else 0

        val iobCalcs = iobCobCalculator.calculateIobFromBolus()
        this.iob = iobCalcs.iob.toFloat() + iobCalcs.basaliob.toFloat()
        this.bg = glucoseStatus.glucose.toFloat()
        this.targetBg = targetBg.toFloat()
        this.cob = mealData.mealCOB.toFloat()
        var lastCarbTimestamp = mealData.lastCarbTime

        if(lastCarbTimestamp.toInt() == 0) {
            val oneDayAgoIfNotFound = now - 24 * 60 * 60 * 1000
            lastCarbTimestamp = iobCobCalculator.getMostRecentCarbByDate() ?: oneDayAgoIfNotFound
        }
        this.lastCarbAgeMin = Math.round(((now - lastCarbTimestamp) / (60 * 1000)).toDouble()).toInt()

        this.futureCarbs = iobCobCalculator.getFutureCob().toFloat()

        this.delta = glucoseStatus.delta.toFloat()
        this.shortAvgDelta = glucoseStatus.shortAvgDelta.toFloat()
        this.longAvgDelta = glucoseStatus.longAvgDelta.toFloat()
        this.noise = glucoseStatus.noise.toFloat()

        this.accelerating_up = if (delta > 2 && delta - longAvgDelta > 2) 1 else 0
        this.deccelerating_up = if (delta > 0 && (delta < shortAvgDelta || delta < longAvgDelta)) 1 else 0
        this.accelerating_down = if (delta < -2 && delta - longAvgDelta < -2) 1 else 0
        this.deccelerating_down = if (delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta)) 1 else 0
        this.stable = if (delta>-3 && delta<3 && shortAvgDelta>-3 && shortAvgDelta<3 && longAvgDelta>-3 && longAvgDelta<3) 1 else 0

        this.tdd7Days = tddCalculator.averageTDD(tddCalculator.calculate(7))?.totalAmount?.toFloat() ?: 0.0f
        this.tdd7DaysPerHour = tdd7Days / 24
        this.tddDaily = tddCalculator.calculateDaily().totalAmount.toFloat()
        this.tddPerHour = tddDaily / (hourOfDay+1)
        this.tdd24Hrs = tddCalculator.calculate24Daily().totalAmount.toFloat()
        this.tdd24HrsPerHour = tdd24Hrs / 24

        this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
        this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
        this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
        this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
        this.recentSteps60Minutes = StepService.getRecentStepCount60Min()

        this.sleep = if ((hourOfDay>21 || hourOfDay<8) && recentSteps30Minutes<50) 1 else 0
        this.sedentary = if(hourOfDay<=21 && hourOfDay>=8 && recentSteps30Minutes<50) 1 else 0

        this.maxIob = sp.getDouble(R.string.key_openapssmb_max_iob, 5.0).toFloat()
        this.maxSMB = sp.getDouble(R.string.key_openapssmb_max_smb, 1.0).toFloat()

        // profile.dia
        val abs = iobCobCalculator.calculateAbsoluteIobFromBaseBasals(System.currentTimeMillis())
        val absIob = abs.iob
        val absNet = abs.netInsulin
        val absBasal = abs.basaliob

        aapsLogger.debug(LTag.APS, "IOB options : bolus iob: ${iobCalcs.iob} basal iob : ${iobCalcs.basaliob}")
        aapsLogger.debug(LTag.APS, "IOB options : calculateAbsoluteIobFromBaseBasals iob: $absIob net : $absNet basal : $absBasal")


        this.profile = JSONObject()
        this.profile.put("max_iob", maxIob)
        this.profile.put("dia", min(profile.dia, 3.0))
        this.profile.put("type", "current")
        this.profile.put("max_daily_basal", profile.getMaxDailyBasal())
        this.profile.put("max_basal", maxBasal)
        this.profile.put("min_bg", minBg)
        this.profile.put("max_bg", maxBg)
        this.profile.put("target_bg", targetBg)
        this.profile.put("carb_ratio", profile.getIc())
        this.profile.put("sens", profile.getIsfMgdl())
        this.profile.put("max_daily_safety_multiplier", sp.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3))
        this.profile.put("current_basal_safety_multiplier", sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4.0))
        this.profile.put("skip_neutral_temps", true)
        this.profile.put("current_basal", basalRate)
        this.profile.put("temptargetSet", tempTargetSet)
        this.profile.put("autosens_adjust_targets", sp.getBoolean(R.string.key_openapsama_autosens_adjusttargets, true))
        //align with max-absorption model in AMA sensitivity
        if (mealData.usedMinCarbsImpact > 0) {
            this.profile.put("min_5m_carbimpact", mealData.usedMinCarbsImpact)
        } else {
            this.profile.put("min_5m_carbimpact", sp.getDouble(R.string.key_openapsama_min_5m_carbimpact, SMBDefaults.min_5m_carbimpact))
        }
        if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            this.profile.put("out_units", "mmol/L")
        }

        val tb = iobCobCalculator.getTempBasalIncludingConvertedExtended(now)
        currentTemp = JSONObject()
        currentTemp.put("temp", "absolute")
        currentTemp.put("duration", tb?.plannedRemainingMinutes ?: 0)
        currentTemp.put("rate", tb?.convertedToAbsolute(now, profile) ?: 0.0)
        // as we have non default temps longer than 30 minutes
        if (tb != null) currentTemp.put("minutesrunning", tb.getPassedDurationToTimeInMinutes(now))

        iobData = iobCobCalculator.convertToJSONArray(iobArray)
        this.glucoseStatus = JSONObject()
        this.glucoseStatus.put("glucose", glucoseStatus.glucose)
        if (sp.getBoolean(R.string.key_always_use_shortavg, false)) {
            this.glucoseStatus.put("delta", glucoseStatus.shortAvgDelta)
        } else {
            this.glucoseStatus.put("delta", glucoseStatus.delta)
        }
        this.glucoseStatus.put("short_avgdelta", glucoseStatus.shortAvgDelta)
        this.glucoseStatus.put("long_avgdelta", glucoseStatus.longAvgDelta)
        this.mealData = JSONObject()
        this.mealData.put("carbs", mealData.carbs)
        this.mealData.put("mealCOB", mealData.mealCOB)
        if (constraintChecker.isAutosensModeEnabled().value()) {
            autosensData.put("ratio", autosensDataRatio)
        } else {
            autosensData.put("ratio", 1.0)
        }
    }

    init {
        injector.androidInjector().inject(this)
        mContext = context
        this.injector = injector
    }
}