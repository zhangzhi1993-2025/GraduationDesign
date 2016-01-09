package cn.edu.njnu;

import cn.edu.njnu.domain.Extractable;
import cn.edu.njnu.infoextract.InfoExtract;
import cn.edu.njnu.tools.Pair;
import cn.edu.njnu.tools.ParameterHelper;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Zhi on 12/28/2015.
 * 线程池的调度单元
 */
public class ProcessUnit implements Runnable {

    //页面存放的根目录
    protected File baseFile;

    //特定类型网页所在的文件夹名
    protected String folderName;

    //所需要使用的信息抽取实例
    protected InfoExtract ie;

    //抽取数据本地输出目录路径
    protected String outputFile;

    //地点与pid的映射
    protected Map<String, String> placeToPid;

    /**
     * 构造器
     *
     * @param config 用于获得目标文件夹与信息抽取实例
     */
    public ProcessUnit(Pair<String, String> config, File file,
                       String outputFile, Map<String, String> placeToPid) {
        try {
            this.outputFile = outputFile;
            this.baseFile = file;
            this.folderName = config.key;
            this.placeToPid = placeToPid;
            this.ie = (InfoExtract) Class.forName(config.value).newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 从指定文件获得内容
     *
     * @param file 指定的文件
     * @return 文件的内容
     */
    protected String getHtml(File file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String buffer;
            while ((buffer = br.readLine()) != null)
                sb.append(buffer);
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 将标记过的内容重新写入文件
     *
     * @param file 待写入的文件
     * @param html 标记过的内容
     */
    protected void writeHtml(File file, String html) {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), "UTF-8"))) {
            bw.write(html);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 上传数据接口
     *
     * @param pid  地点的id号
     * @param info 待上传的数据
     */
    protected boolean postData(String pid, List<Extractable> info) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost method = new HttpPost(new ParameterHelper().getPostDataURL());
            JSONObject data = new JSONObject();
            JSONArray array = new JSONArray();
            for (Extractable extractable : info) {
                String title = " ";
                String time = " ";
                String content = " ";
                JSONObject other = new JSONObject();
                for (Pair<String, String> pair : extractable) {
                    if (pair.key.contains("标题"))
                        title = pair.value;
                    else if (pair.key.contains("时间"))
                        time = pair.value;
                    else if (pair.key.contains("内容"))
                        content = pair.value;
                    else
                        other.put(pair.key, pair.value);
                }
                //if (title.equals("") || time.equals("") || content.equals(""))
                // continue;
                JSONObject item = new JSONObject();
                item.put("title", title);
                item.put("type", ie.getType());
                item.put("time", time);
                item.put("content", content);
                item.put("pid", pid);
                item.put("pic", "http://img0.pconline.com.cn/pconline" +
                        "/1308/06/3415302_3cbxat8i1_bdls7k5b.jpg");
                item.put("other", other);
                array.put(item);
            }
            if (array.length() == 0)
                return false;
            data.put("acs", array);
            //生成参数对
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("data", data.toString()));
            UrlEncodedFormEntity entity = new UrlEncodedFormEntity(params, "UTF-8");
            method.setEntity(entity);

            //请求post
            HttpResponse result = httpClient.execute(method);
            String resData = EntityUtils.toString(result.getEntity());
            //获得结果
            JSONObject resJson = JSONObject.fromObject(resData);
            if (resJson.getInt("code") == 1) {
                JSONObject result2 = resJson.getJSONObject("data");
                return result2.getInt("status") == 1;
            } else
                return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 便于stream api使用方法引用特性,将处理过程封装
     *
     * @param f 待分析的页面文件
     */
    protected void process(File f, String place) {
        String html = getHtml(f);

        //检测html开头是否有标记URL,若有则说明该页面没有被访问过,提取URL并将其去除;
        //若没有则说明已访问过,跳过之;
        Pattern pattern = Pattern.compile("https?://[\\w./]+");
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find())
            return;
        int index = 0;
        while (html.charAt(index) != '<')
            index++;
        String url = html.substring(0, index);
        html = html.substring(index, html.length() - 1);
        //将除去URL标记的html重新写入页面文件中
        writeHtml(f, html);

        List<Extractable> info = ie.extractInformation(html);
        if (info != null) {
            if (placeToPid.containsKey(place)) {
                boolean hasPost = postData(placeToPid.get(place), info);
                info.forEach(extraction -> {
                    try {
                        extraction.persistData(outputFile, url, hasPost);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
    }

    /**
     * 递归搜索每个文件夹,找到目标文件夹作处理过程
     *
     * @param current 当前的文件夹
     */
    protected void searchForTarget(File current) {
        File[] list = current.listFiles();
        if (list != null) {
            if (current.getName().equals(folderName)) {
                String place = current.getParentFile().getName();
                for (File file : list)
                    process(file, place);
            } else {
                if (list[0].isFile())
                    return;
                Arrays.stream(list).forEach(this::searchForTarget);
            }
        }
    }

    @Override
    public void run() {
        searchForTarget(baseFile);
    }

}
