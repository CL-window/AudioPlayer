package slack.cl.com.audioplayer;

/**
 * Created by slack
 * on 17/11/3 下午5:59
 */

public class PrecisionUtil {
    /**
     * float的精度， 1: 10.2; 2: 10.21;...
     *
     * 10.2145 * 100 / 100 = 10.21
     */
    private static int mShowValuePrecision = 1;

    public static String formTextByPrecision(float value) {
        int key = (int) Math.pow(10, mShowValuePrecision);
        return ((float)(Math.round(value*key))/key) + "";
    }
}
