#pragma once
// Included inside namespace online; hardware and transport are supplied by the host.
// UTF-8 wrapping keeps all text available; never truncate the original question or method.
String lines[64];int lineCount=0;
void wrap(String s) {
  String line;
  for(unsigned i=0;i<s.length();) {
    unsigned char c=s[i];int n=c<128?1:(c<224?2:(c<240?3:4));
    String glyph=s.substring(i,i+n);i+=n;
    if(glyph=="\n") {if(lineCount<64)lines[lineCount++]=line;line="";continue;}
    if(chinese.getUTF8Width((line+glyph).c_str())>288) {if(lineCount<64)lines[lineCount++]=line;line="";}
    line+=glyph;
  }
  if(line.length()&&lineCount<64)lines[lineCount++]=line;
}
int solvedCount(){if(state.containsKey("solved_total"))return state["solved_total"].as<int>();int count=0;for(auto t:tasks())if(t["available"]==true&&t["state"]=="solved")++count;return count;}
int availableCount(){if(state.containsKey("available_total"))return state["available_total"].as<int>();int count=0;for(auto t:tasks())if(t["available"]==true)++count;return count;}
bool allSolved(){return availableCount()>0&&solvedCount()==availableCount();}
void openTask(int index){
  if(index<0||index>=(int)tasks().size())return;
  selected=index;if(!task()["available"].as<bool>())return;
  contentPage=0;detailKind=0;
  if(task()["state"]=="solved"){detail=task()["explanation"]|question()["solution"]["explanation"].as<String>();screen=3;}
  else screen=2;
}
void continueAdventure(){
  for(int step=1;step<=(int)tasks().size();++step){int index=(selected+step)%tasks().size();if(tasks()[index]["available"]==true&&tasks()[index]["state"]!="solved"){openTask(index);return;}}
  screen=1;mapOffset=(selected/2)*2;
}
void render() {
  dirty=false;buttonCount=0;lcd.fillSprite(cream);lcd.fillRect(0,0,320,28,pale);
  struct HeaderIcon {~HeaderIcon(){statusHeader();}} headerIcon;
  text("小语冒险岛",8,20);text(busy?"同步中":mode=="live"?"正式":(mode=="practice"?"试玩":"联网"),244,20);
  if(screen==5) {
    lcd.fillRect(0,0,320,28,pale);text("输入四位配对码",8,20);text(pairCode.c_str(),248,20);
    for(int i=0;i<9;++i){String digit=String(i+1);button(8+(i%3)*80,40+(i/3)*48,72,digit.c_str(),150+i);}
    button(88,188,152,"0",159);
    button(248,40,64,"返回",104,false);button(248,88,64,"删除",160,false);button(248,136,64,"确认",161);return;
  }
  if(screen==7){
    centered("配对前先连通网络",62);centered(message.c_str(),108);
    button(8,136,148,"检查连接",140,false);button(164,136,148,"Wi-Fi设置",104,false);
    button(72,188,176,"返回首页",100);return;
  }
  if(screen==6) {
    centered("请在家长网页确认设备",60);centered(apName.length()?apName.c_str():"Math-ESP32",92);
    centered("核对校验号后确认绑定",124);centered(pairVerification.c_str(),154);
    button(72,188,176,"返回设置",104,false);return;
  }
  if(screen==4) {
    text(base.startsWith("http://192.168.")?"当前：局域网":"当前：公网",8,48);
    lcd.setTextColor(ink,cream);lcd.drawString(base,8,64,2);
    text(connectionLabel().c_str(),8,82);
    button(8,88,96,"局域网",186,false,36);button(112,88,96,"公网",187,false,36);button(216,88,96,"配网",103,false,36);
    text(networkDetails().c_str(),8,143);
    button(8,148,96,"校准",41,false,34);button(112,148,96,"检查连接",188,false,34);button(216,148,96,"配对",140,false,34);
    button(72,188,176,"返回首页",100);return;
  }
  if(screen==8) {
    lcd.fillRect(0,0,320,28,pale);text(("填写数字："+numberInput).c_str(),8,20);
    for(int i=0;i<9;++i){String digit=String(i+1);button(8+(i%3)*80,40+(i/3)*48,72,digit.c_str(),170+i);}
    button(8,188,72,"负号",180,false);button(88,188,72,"0",179);button(168,188,72,"小数点",181,false);
    button(248,40,64,"返回",182,false);button(248,88,64,"删除",183,false);button(248,136,64,"确认",184);return;
  }
  if(screen==9){
    face(64,108,true);
    text(allSolved()?"今天的星星集齐啦！":"又点亮一颗星！",104,63);
    text("你找到好办法啦",104,94);
    String progress=String(solvedCount())+" / "+String(availableCount())+" 颗冒险星";
    text(progress.c_str(),104,123);
    for(int i=0;i<min(availableCount(),5);++i)star(115+i*36,153,i<solvedCount());
    button(8,188,96,"看方法",191,false);button(112,188,200,allSolved()?"看看星星地图":"继续冒险",190);return;
  }
  if(screen==0) {
    face(54,107,true);
    text(allSolved()?"今天的冒险完成啦":"和小向导一起出发",100,64);
    char buf[50];snprintf(buf,sizeof(buf),"经验 %d  金币 %d",xp,coins);text(buf,100,94);
    String journey=ready?(availableCount()?String("已点亮 ")+solvedCount()+" / "+availableCount()+" 颗星":"等家长准备新冒险"):"等待同步冒险地图";
    text(journey.c_str(),100,125);
    text(message.c_str(),8,171);
    button(8,188,96,"小设置",104,false);button(112,188,200,authInvalid?"重新配对设备":credential.isEmpty()?"配对设备":(allSolved()?"看看我的星星":"开始今日冒险"),(credential.isEmpty()||authInvalid)?140:101);return;
  }
  if(screen==1) {
    lcd.fillRect(0,0,320,28,pale);text(allSolved()?"星星集齐，休息一下吧":"今日星星地图",8,20);
    String progress=String(solvedCount())+" / "+availableCount();text(progress.c_str(),250,20);
    if(!tasks().size())centered("等家长准备新冒险",90);
    if((int)tasks().size()>mapOffset+1)lcd.drawFastVLine(23,54,44,0x9ED5);
    for(unsigned i=mapOffset;i<tasks().size()&&i<(unsigned)(mapOffset+2);++i) {
      int row=i-mapOffset;
      auto t=tasks()[i];String title=t["question"]["title"]|"暂时休息";
      String shortTitle;
      for(unsigned j=0;j<title.length();) {unsigned char c=title[j];int n=c<128?1:(c<224?2:3);String next=title.substring(j,j+n);if(chinese.getUTF8Width((shortTitle+next).c_str())>124)break;shortTitle+=next;j+=n;}
      String st=t["state"]|"pending";star(23,54+row*44,st=="solved");
      text(shortTitle.c_str(),42,56+row*44);
      button(180,32+row*44,132,!t["available"].as<bool>()?"暂时休息":st=="solved"?"已点亮·看方法":(st=="skipped"?"回来试试":"出发闯关"),110+i,st!="solved",36);
    }
    bool previous=mapOffset>0||serverPage>0,next=mapOffset+2<(int)tasks().size()||state["has_more"]==true;
    if(previous)button(8,144,72,"上一页",193,false,36);
    button(88,144,144,"随机探索",192,true,36);
    if(next)button(240,144,72,"下一页",194,false,36);
    // Two compact task rows end at y=114 (including shadows). Keep the
    // 16px status text in y=118..134, above navigation at y=144..182.
    String status=mapMessage.length()?mapMessage:busy?"正在同步，可继续查看当前关卡":"随机探索一题，发现新挑战";
    String statusLine;
    for(unsigned j=0;j<status.length();){unsigned char c=status[j];int n=c<128?1:(c<224?2:(c<240?3:4));String glyph=status.substring(j,j+n);if(chinese.getUTF8Width((statusLine+glyph).c_str())>288){statusLine+="…";break;}statusLine+=glyph;j+=n;}
    text(statusLine.c_str(),8,134,0x5B6B);
    button(8,188,148,"回家休息",100,false);button(164,188,148,"更新地图",102,false);return;
  }
  lineCount=0;
  if(screen!=3)wrap(question()["content"].as<String>());
  // Short choices live only on the answer buttons. If any choice cannot
  // fit, paginate all choices together and use letter-only answer buttons.
  bool choicesInBody=false;
  if(screen!=3)for(unsigned i=0;i<question()["options"].size();++i){
    String label=String(char('A'+i))+". "+question()["options"][i].as<String>();
    if(chinese.getUTF8Width(label.c_str())>88||label.indexOf('\n')>=0)choicesInBody=true;
  }
  if(screen!=3&&choicesInBody)for(unsigned i=0;i<question()["options"].size();++i)wrap(String(char('A'+i))+". "+question()["options"][i].as<String>());
  lcd.fillRect(0,0,320,28,pale);
  String caption=screen==3?(detailKind==1?"小向导的提示":detailKind==2?"换个办法，再试一次":"一起看看好方法"):(task()["kind"]=="explore"?String("探索 · "):String("第 ")+(serverPage*5+selected+1)+" 关 · ")+(question()["question_type"]=="number"?"填一填":"选一选");
  text(caption.c_str(),8,20);
  if(screen==3)wrap(detail);
  int pages=(lineCount+3)/4;if(pages<1)pages=1;if(contentPage>=pages)contentPage=0;
  for(int j=0;j<4;++j)if(contentPage*4+j<lineCount)text(lines[contentPage*4+j].c_str(),12,45+j*20);
  String pageNote=pages>1?String("第 ")+(contentPage+1)+" / "+pages+(contentPage<pages-1?" 页 · 点翻页继续":" 页 · 已到最后一页"):(screen==2?"慢慢想，选好后轻点答案":"看懂了就回去试一试");
  text(pageNote.c_str(),12,124,0x5B6B);
  if(screen==2) {
    auto opts=question()["options"].as<JsonArray>();
    // Complete long choices are above; their buttons only identify the choice.
    if(question()["question_type"]=="number"&&contentPage==pages-1) button(8,130,304,"填写数字答案",185);
    else if(question()["question_type"]!="number"&&contentPage==pages-1) {
      for(unsigned i=0;i<opts.size()&&i<3;++i) {
        String prefix=String(char('A'+i))+". ";String label=prefix+opts[i].as<String>();
        if(choicesInBody) label=String("选 ")+char('A'+i);
        button(8+i*104,130,96,label.c_str(),120+i);
      }
    }
    if(contentPage<pages-1)centered("读完这一页，点翻页继续",146);
    if(pages>1){
      button(8,188,72,"帮帮我",130,false);button(88,188,72,"稍后",131,false);
      button(168,188,72,"翻页",136,false);button(248,188,64,"地图",132,false);
    }else{
      button(8,188,96,"帮帮我",130,false);button(112,188,96,"稍后再想",131,false);button(216,188,96,"星星地图",132,false);
    }
  } else {
    button(8,188,96,task()["state"]=="solved"?"继续冒险":"再试一次",task()["state"]=="solved"?190:133);
    button(112,188,96,pages>1?"翻页":task()["state"]=="solved"?"再看题目":"帮帮我",pages>1?136:task()["state"]=="solved"?133:130,false);button(216,188,96,"星星地图",135,false);
  }
}
void handle(int id) {
  if(id==186||id==187){if(busy){message="正在请求，请稍后切换";dirty=true;return;}base=id==186?"http://192.168.0.160/math":"https://ryven.xin/math";config.putString("base",base);message=id==186?"已切换局域网":"已切换公网";resetConnection();dirty=true;return;}
  if(id==188){if(!busy){if(wifiConfiguredOnline()){message="正在检查服务器";send("/health");}else message="Wi-Fi未连接，请重新配网";}dirty=true;return;}
  if(id==140) {beginPair();return;}
  if(screen==5&&id>=150&&id<=159) {if(pairCode.length()<4)pairCode+=String(id==159?0:id-149);dirty=true;return;}
  if(id==161&&screen==5){if(!busy)claimPair();return;}
  if(id==160) {if(pairCode.length())pairCode.remove(pairCode.length()-1);dirty=true;return;}
  if(id==100) {pairOpenRequested=false;screen=0;dirty=true;return;}
  if(id==104) {pairOpenRequested=false;screen=4;dirty=true;return;}
  if(id==103) {startPortal();return;}
  if(id==101) {screen=1;dirty=true;if(!ready)sync();return;}
  if(id==102) {sync();return;}

  if(id==192){requestExploration();return;}
  if(id==193){if(mapOffset>0)mapOffset-=2;else if(serverPage>0){--serverPage;mapOffset=4;refreshAfter=true;nextRetry=0;}dirty=true;return;}
  if(id==194){if(mapOffset+2<(int)tasks().size())mapOffset+=2;else if(state["has_more"]==true){++serverPage;mapOffset=0;refreshAfter=true;nextRetry=0;}dirty=true;return;}
  if(id==190){continueAdventure();dirty=true;return;}
  if(id==191){detail=question()["solution"]["explanation"].as<String>();detailKind=0;contentPage=0;screen=3;dirty=true;return;}
  if(id==185){numberInput="";screen=8;dirty=true;return;}
  if(screen==8){
    if(id>=170&&id<=179&&numberInput.length()<12)numberInput+=String(id==179?0:id-169);
    if(id==180){if(numberInput.startsWith("-"))numberInput.remove(0,1);else numberInput="-"+numberInput;}
    if(id==181&&numberInput.indexOf('.')<0)numberInput+=".";
    if(id==183&&numberInput.length())numberInput.remove(numberInput.length()-1);
    if(id==182)screen=2;
    if(id==184&&numberInput!=""&&numberInput!="-"&&numberInput!="."&&numberInput!="-.")submit("/api/math/answers",-1,true);
    dirty=true;return;
  }
  if(id>=110&&id<115)openTask(id-110);
  if(id>=120&&id<123)submit("/api/math/answers",id-120);
  if(id==130)submit("/api/math/tasks/hint");
  if(id==131)submit("/api/math/tasks/skip");
  if(id==132||id==135){screen=1;mapOffset=(selected/2)*2;}
  if(id==136)contentPage++;
  if(id==133){screen=2;contentPage=0;}
  if(id==134){if(lineCount>4)contentPage++;else screen=1;}
  dirty=true;
}
