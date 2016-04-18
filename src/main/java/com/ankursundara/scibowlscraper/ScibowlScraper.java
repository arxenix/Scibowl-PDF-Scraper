package com.ankursundara.scibowlscraper;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.util.PDFTextStripper;
import org.apache.pdfbox.util.TextPosition;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Ankur on 2/7/2016.
 */
public class ScibowlScraper extends PDFTextStripper {

    private static int pageNo;
    private static boolean is1stChar;
    private static String lastYVal;

    private static double cx,cy;
    private static StringBuilder tWord;
    private static Map<Integer, List<TextToken>> pageTokens = new HashMap<Integer, List<TextToken>>();
    private ScibowlScraper() throws IOException {
        super.setSortByPosition(true);
    }

    public static void main(String[] args) throws IOException {
        File hsdir = new File("hs_pdfs");
        File msdir = new File("ms_pdfs");

        String[] loggers = { "org.apache.pdfbox.util.PDFStreamEngine",
                "org.apache.pdfbox.pdmodel.font.PDSimpleFont",
                "org.apache.pdfbox.pdmodel.font.PDFont",
                "org.apache.pdfbox.pdmodel.font.FontManager",
                "org.apache.pdfbox.pdfparser.PDFObjectStreamParser" };
        for (String logger : loggers) {
            org.apache.log4j.Logger logpdfengine = org.apache.log4j.Logger
                    .getLogger(logger);
            logpdfengine.setLevel(org.apache.log4j.Level.OFF);
        }

        if(msdir.isDirectory() && hsdir.isDirectory()) {
            parsePdfs(hsdir, "hs");
            parsePdfs(msdir, "ms");
            json.put("questions",questions);

            System.out.println("saving JSON...");
            try (FileWriter file = new FileWriter("questions.json")) {
                file.write(json.toString(4));
            }
        }
        else {
            System.out.println("Downloading pdfs... you may need to clean these up manually (remove non-question pdfs)!");
            try {

                URL hsUrl = new URL("http://science.energy.gov/wdts/nsb/high-school/high-school-regionals/hs-rules-forms-resources/sample-science-bowl-questions/");
                URL msUrl = new URL("http://science.energy.gov/wdts/nsb/middle-school/middle-school-regionals/ms-rules-forms-resources/sample-questions/");
                downloadPdfs(hsUrl, "hs_pdfs");
                downloadPdfs(msUrl, "ms_pdfs");
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
    }
    static JSONObject json = new JSONObject();

    static List<JSONObject> questions = new ArrayList<>();
    public static void parsePdfs(File dir, String level) throws IOException {
        ScibowlScraper scraper = new ScibowlScraper();


        int progress = 1;

        int totalPDFS = 0;
        for(File setdir:dir.listFiles()) {
            for(File f:setdir.listFiles()) {
                if(FilenameUtils.getExtension(f.getAbsolutePath()).equals("pdf")) {
                    totalPDFS++;
                }
            }
        }

        for(File setdir:dir.listFiles()) {
            if(setdir.isDirectory()) {
                System.out.println("Parsing set: "+setdir.getName());
                for(File f:setdir.listFiles()) {
                    System.out.println("Parsing "+f.getName() + "("+( (progress++) +"/"+totalPDFS)+")");
                    if(FilenameUtils.getExtension(f.getAbsolutePath()).equals("pdf")) {
                        try {
                            PDDocument doc = PDDocument.load(f);
                            pageNo = 1;
                            is1stChar = true;
                            tWord = new StringBuilder();
                            lastYVal = "";
                            pageTokens = new HashMap<Integer, List<TextToken>>();
                            cx=0;
                            cy=0;

                            List allPages = doc.getDocumentCatalog().getAllPages();
                            for (Object allPage : allPages) {
                                System.out.println("Reading page - "+pageNo);
                                PDPage page = (PDPage) allPage;
                                PDStream contents = page.getContents();

                                pageTokens.put(pageNo, new ArrayList<TextToken>());
                                if (contents != null) {
                                    scraper.processStream(page, page.findResources(), contents.getStream());
                                }
                                endWord();


                                List<TextToken> cropTokens = new ArrayList<TextToken>();
                                List<TextToken> tokens = pageTokens.get(pageNo);

                                JSONObject question = new JSONObject();
                                int qid = 0;
                                for (TextToken t : tokens) {
                                    //System.out.println(t);
                                    if (t.text.trim().equals("TOSS-UP") || t.text.trim().equals("BONUS")) {
                                        String roundname = FilenameUtils.removeExtension(f.getName());
                                        question = new JSONObject();
                                        question.put("bonus", t.text.trim().equals("BONUS"));
                                        question.put("level", level.toUpperCase());
                                        question.put("set_name", setdir.getName());
                                        question.put("round_name", roundname);
                                        question.put("page", pageNo);
                                        question.put("num",qid);
                                        question.put("question_image", setdir.getName()+"/"+roundname+"/"+pageNo+"_"+qid+".png");
                                        question.put("answer_image", setdir.getName()+"/"+roundname+"/"+pageNo+"_"+qid+"_ans.png");
                                        cropTokens.add(t);
                                    }
                                    if (t.text.trim().startsWith("ANSWER")) {
                                        if(question.has("bonus")) {
                                            question.put("parsed_answer", t.text.replace("ANSWER:", "").trim());
                                            questions.add(question);
                                            cropTokens.add(t);
                                            qid++;
                                        }
                                        else {
                                            System.out.println("##ERROR## - "+setdir.getName()+"/"+FilenameUtils.removeExtension(f.getName())+"/"+pageNo+"/"+qid);
                                        }

                                    }
                                    if (t.text.trim().matches("\\*?\\d{1,3}\\) .*")) {
                                        if(!question.has("category") && question.has("bonus")) {
                                            question.put("category", t.text.replaceFirst("\\*?\\d{1,3}\\) ","").split("(Short|Multiple)")[0].replace(".","").trim().replaceAll(" +", " "));
                                            if(t.text.contains("Multiple Choice")) question.put("type", "MC");
                                            else question.put("type", "SA");
                                        }
                                        else {
                                            System.out.println("##ERROR## - "+setdir.getName()+"/"+FilenameUtils.removeExtension(f.getName())+"/"+pageNo+"/"+qid);
                                        }
                                    }
                                }


                                BufferedImage pgImg = page.convertToImage();

                                for(int i=0;i<cropTokens.size();i++) {
                                    TextToken t = cropTokens.get(i);
                                    String roundname = FilenameUtils.removeExtension(f.getName());

                                    BufferedImage croppedImage;
                                    if(i==cropTokens.size()-1) {
                                        croppedImage = cropImage(pgImg, (int)t.y*2-30, (int)t.y*2+60/*pgImg.getHeight()-110*/);
                                    }
                                    else croppedImage = cropImage(pgImg, (int)t.y*2-30, (int)cropTokens.get(i+1).y*2-30);

                                    String ext = ".png";
                                    if(i%2==1) {
                                        //answer
                                        ext="_ans.png";
                                    }
                                    File toWrite = new File("images"+File.separator+level,setdir.getName()+"/"+roundname+"/"+pageNo+"_"+(i/2)+ext);
                                    toWrite.getParentFile().mkdirs();
                                    ImageIO.write(trimImage(croppedImage, 0.05), "png", toWrite);
                                }

                                pageNo += 1;
                            }
                            doc.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }


    @Override
    protected void processTextPosition(TextPosition text) {
        String tChar = text.getCharacter();
        String REGEX = "(\\x0A|[\\x20-\\x7F])";
        boolean lineMatch = matchCharLine(text);
        if ((tChar.matches(REGEX)) /*&& (!Character.isWhitespace(c))*/) {
            if (!is1stChar && lineMatch) {
                appendChar(tChar);
            } else if (is1stChar) {
                setWordCoord(text, tChar);
            }
        } else {
            endWord();
        }
    }


    private void appendChar(String tChar) {
        tWord.append(tChar);
    }

    private void setWordCoord(TextPosition text, String tChar) {
        cx = text.getXDirAdj();
        cy = text.getYDirAdj();
        tWord.append(tChar);
        is1stChar = false;
    }

    private static void endWord() {
        pageTokens.get(pageNo).add(new TextToken(tWord.toString(), cx,cy));
        tWord.delete(0, tWord.length());
        is1stChar = true;
    }

    private boolean matchCharLine(TextPosition text) {
        String yVal = roundVal(text.getYDirAdj());
        if (yVal.equals(lastYVal)) {
            return true;
        }
        lastYVal = yVal;
        endWord();
        return false;
    }

    private String roundVal(Float yVal) {
        DecimalFormat rounded = new DecimalFormat("0.0'0'");
        return rounded.format(yVal);
    }

    private static void downloadPdfs(URL hsUrl, String dir) throws MalformedURLException {
        try {
            String hsContent = IOUtils.toString(hsUrl);
            Pattern pattern = Pattern.compile("<a\\s+(?:[^>]*?\\s+)?href=\"([^\"]*)\"");

            Matcher matcher = pattern.matcher(hsContent);
            while (matcher.find()) {
                String hreftag = matcher.group(1);
                if(hreftag.endsWith(".pdf")) {
                    System.out.println(hreftag);
                    URL pdfUrl = new URL("http://science.energy.gov"+hreftag);
                    String[] split = hreftag.split("/");
                    FileUtils.copyURLToFile(pdfUrl, new File(dir, split[7]+File.separator+split[8]));
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static BufferedImage cropImage(BufferedImage src, int y1, int y2) {
        return src.getSubimage(0, y1, src.getWidth(), y2-y1);
    }

    private static BufferedImage trimImage(BufferedImage source, double tolerance) {
        // Get our top-left pixel color as our "baseline" for cropping
        int baseColor = source.getRGB(0, 0);

        int width = source.getWidth();
        int height = source.getHeight();

        int topY = Integer.MAX_VALUE, topX = Integer.MAX_VALUE;
        int bottomY = -1, bottomX = -1;
        for(int y=0; y<height; y++) {
            for(int x=0; x<width; x++) {
                if (colorWithinTolerance(baseColor, source.getRGB(x, y), tolerance)) {
                    if (x < topX) topX = x;
                    if (y < topY) topY = y;
                    if (x > bottomX) bottomX = x;
                    if (y > bottomY) bottomY = y;
                }
            }
        }

        BufferedImage destination = new BufferedImage( (bottomX-topX+1),
                (bottomY-topY+1), source.getType());

        destination.getGraphics().drawImage(source, 0, 0,
                destination.getWidth(), destination.getHeight(),
                topX, topY, bottomX, bottomY, null);

        return destination;
    }

    private static boolean colorWithinTolerance(int a, int b, double tolerance) {
        int aAlpha  = (int)((a & 0xFF000000) >>> 24);   // Alpha level
        int aRed    = (int)((a & 0x00FF0000) >>> 16);   // Red level
        int aGreen  = (int)((a & 0x0000FF00) >>> 8);    // Green level
        int aBlue   = (int)(a & 0x000000FF);            // Blue level

        int bAlpha  = (int)((b & 0xFF000000) >>> 24);   // Alpha level
        int bRed    = (int)((b & 0x00FF0000) >>> 16);   // Red level
        int bGreen  = (int)((b & 0x0000FF00) >>> 8);    // Green level
        int bBlue   = (int)(b & 0x000000FF);            // Blue level

        double distance = Math.sqrt((aAlpha-bAlpha)*(aAlpha-bAlpha) +
                (aRed-bRed)*(aRed-bRed) +
                (aGreen-bGreen)*(aGreen-bGreen) +
                (aBlue-bBlue)*(aBlue-bBlue));

        double percentAway = distance / 510.0d;

        return (percentAway > tolerance);
    }

    private static class TextToken {
        String text;
        double x,y;

        TextToken(String text, double x, double y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }

        public String toString() {
            return text +" ("+x+","+y+")";
        }
    }
}
