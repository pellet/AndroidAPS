package info.nightscout.androidaps.plugins.aps.openAPSaiSMB

import dagger.android.HasAndroidInjector
import info.nightscout.shared.logging.LTag
import info.nightscout.androidaps.plugins.aps.loop.APSResult
import org.json.JSONException
import org.json.JSONObject

class DetermineBasalResultaiSMB private constructor(injector: HasAndroidInjector) : APSResult(injector) {

    var constraintStr:String = ""
    var glucoseStr:String = ""
    var iobStr:String = ""
    var profileStr:String = ""
    var mealStr:String = ""

    internal constructor(
        injector: HasAndroidInjector,
        requestedSMB: Float,
        constraintStr: String,
        glucoseStr: String,
        iobStr: String,
        profileStr: String,
        mealStr: String,
        reason: String
    ) : this(injector) {
        this.constraintStr = constraintStr
        this.glucoseStr = glucoseStr
        this.iobStr = iobStr
        this.profileStr = profileStr
        this.mealStr = mealStr

        date = dateUtil.now()

        tempBasalRequested = true
        rate = 0.0
        duration = 120

        smb = requestedSMB.toDouble()
        if (requestedSMB > 0) {
            deliverAt = dateUtil.now()
        }

        this.reason = reason
    }

    override fun newAndClone(injector: HasAndroidInjector): DetermineBasalResultaiSMB {
        val newResult = DetermineBasalResultaiSMB(injector)
        doClone(newResult)
        return newResult
    }

    override fun json(): JSONObject? {
        try {
            return JSONObject(json.toString())
        } catch (e: JSONException) {
            aapsLogger.error(LTag.APS, "Unhandled exception", e)
        }
        return null
    }

    init {
        hasPredictions = true
    }
}